package com.tj.crypto.observability;

import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.marketdata.connector.MarketDataConnector;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.FundingRateEvent;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.OpenInterestEvent;
import com.tj.crypto.strategy.core.SignalCollector;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统指标收集器。
 * 收集事件吞吐量、信号数量、连接状态、延迟分布等指标。
 *
 * 设计决策：
 * - 使用 AtomicLong 计数器，无锁高性能
 * - 延迟采样使用循环缓冲区，避免 GC 压力
 * - 定时输出到日志（后续可接入 Micrometer/Prometheus）
 * - 轻量级，不影响主流程
 */
@Slf4j
@Component
public class SystemMetrics {

    private static final int LATENCY_BUFFER_SIZE = 4096;

    private final MarketEventBus eventBus;
    private final SignalCollector signalCollector;
    private final List<MarketDataConnector> connectors;

    private final AtomicLong barEventCount = new AtomicLong(0);
    private final AtomicLong liquidationEventCount = new AtomicLong(0);
    private final AtomicLong fundingRateEventCount = new AtomicLong(0);
    private final AtomicLong openInterestEventCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong totalEventCount = new AtomicLong(0);

    /** 持久化队列深度（由持久化模块调增减） */
    private final AtomicLong persistenceQueueDepth = new AtomicLong(0);

    /** 事件处理延迟采样（微秒精度，循环缓冲区） */
    private final long[] eventLatencyBuffer = new long[LATENCY_BUFFER_SIZE];
    private final AtomicLong eventLatencyIndex = new AtomicLong(0);

    /** 策略执行延迟采样 */
    private final long[] strategyLatencyBuffer = new long[LATENCY_BUFFER_SIZE];
    private final AtomicLong strategyLatencyIndex = new AtomicLong(0);

    /**
     * 手动注册的连接状态（用于非 MarketDataConnector 实现）。
     */
    private final Map<String, ConnectorHealth> connectorHealthMap = new ConcurrentHashMap<>();

    public SystemMetrics(MarketEventBus eventBus,
                         SignalCollector signalCollector,
                         List<MarketDataConnector> connectors) {
        this.eventBus = eventBus;
        this.signalCollector = signalCollector;
        this.connectors = connectors;
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(BarEvent.class, e -> recordEvent(barEventCount));
        eventBus.subscribe(LiquidationEvent.class, e -> recordEvent(liquidationEventCount));
        eventBus.subscribe(FundingRateEvent.class, e -> recordEvent(fundingRateEventCount));
        eventBus.subscribe(OpenInterestEvent.class, e -> recordEvent(openInterestEventCount));
        log.info("SystemMetrics initialized with {} connector(s)", connectors.size());
    }

    private void recordEvent(AtomicLong counter) {
        counter.incrementAndGet();
        totalEventCount.incrementAndGet();
    }

    /**
     * 记录事件处理延迟。
     * 由事件总线或策略引擎调用。
     *
     * @param latencyMs 延迟（毫秒）
     */
    public void recordEventProcessingLatency(double latencyMs) {
        int idx = (int) (eventLatencyIndex.getAndIncrement() % LATENCY_BUFFER_SIZE);
        eventLatencyBuffer[idx] = (long) (latencyMs * 1000); // 存储为微秒
    }

    /**
     * 记录策略执行耗时。
     * 由 StrategyEngine 调用。
     *
     * @param latencyMs 耗时（毫秒）
     */
    public void recordStrategyExecutionLatency(double latencyMs) {
        int idx = (int) (strategyLatencyIndex.getAndIncrement() % LATENCY_BUFFER_SIZE);
        strategyLatencyBuffer[idx] = (long) (latencyMs * 1000); // 存储为微秒
    }

    /**
     * 记录一次错误。
     */
    public void recordError() {
        errorCount.incrementAndGet();
    }

    /**
     * 增加持久化队列深度。
     */
    public void incrementPersistenceQueue() {
        persistenceQueueDepth.incrementAndGet();
    }

    /**
     * 减少持久化队列深度。
     */
    public void decrementPersistenceQueue() {
        persistenceQueueDepth.decrementAndGet();
    }

    /**
     * 每分钟输出一次指标。
     */
    @Scheduled(fixedRate = 60000)
    public void reportMetrics() {
        long bars = barEventCount.getAndSet(0);
        long liquidations = liquidationEventCount.getAndSet(0);
        long fundingRates = fundingRateEventCount.getAndSet(0);
        long openInterests = openInterestEventCount.getAndSet(0);
        long errors = errorCount.getAndSet(0);

        double eventP99 = calculateP99(eventLatencyBuffer, eventLatencyIndex.get());
        double strategyP99 = calculateP99(strategyLatencyBuffer, strategyLatencyIndex.get());

        log.info("[METRICS] Events/min - Bar: {}, Liquidation: {}, FundingRate: {}, OpenInterest: {}, Errors: {} | "
                        + "Event P99: {}ms, Strategy P99: {}ms, Queue: {} | Total signals: {}",
                bars, liquidations, fundingRates, openInterests, errors,
                String.format("%.1f", eventP99), String.format("%.1f", strategyP99),
                persistenceQueueDepth.get(),
                signalCollector.getAllSignals().size());

        reportConnectorHealth();
    }

    /**
     * 报告所有连接器的健康状态。
     */
    private void reportConnectorHealth() {
        for (MarketDataConnector connector : connectors) {
            ConnectorHealth health = connector.health();
            if (!health.connected()) {
                log.warn("[METRICS] Connector disconnected: messagesReceived={}, reconnectCount={}, lastError={}",
                        health.messagesReceived(), health.reconnectCount(), health.lastError());
            }
        }

        connectorHealthMap.forEach((name, health) -> {
            if (!health.connected()) {
                log.warn("[METRICS] {} disconnected: messagesReceived={}, reconnectCount={}, lastError={}",
                        name, health.messagesReceived(), health.reconnectCount(), health.lastError());
            }
        });
    }

    /**
     * 计算 P99 百分位数（毫秒）。
     * 对缓冲区副本排序后取第 99 百分位。
     */
    private double calculateP99(long[] buffer, long count) {
        if (count == 0) {
            return 0.0;
        }
        int samples = (int) Math.min(count, LATENCY_BUFFER_SIZE);
        long[] copy = new long[samples];
        System.arraycopy(buffer, 0, copy, 0, samples);
        Arrays.sort(copy);
        int p99Index = (int) Math.ceil(samples * 0.99) - 1;
        p99Index = Math.max(0, Math.min(p99Index, samples - 1));
        return copy[p99Index] / 1000.0; // 微秒转毫秒
    }

    /**
     * 计算平均值（毫秒）。
     */
    private double calculateAvg(long[] buffer, long count) {
        if (count == 0) {
            return 0.0;
        }
        int samples = (int) Math.min(count, LATENCY_BUFFER_SIZE);
        long sum = 0;
        for (int i = 0; i < samples; i++) {
            sum += buffer[i];
        }
        return (sum / (double) samples) / 1000.0; // 微秒转毫秒
    }

    /**
     * 生成指标快照。
     * 供 AlertService 等组件读取当前指标状态。
     */
    public MetricsSnapshot snapshot() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryUsedPct = totalMemory > 0 ? (usedMemory * 100.0 / totalMemory) : 0;

        long total = totalEventCount.get();
        long errors = errorCount.get();
        double errorRate = total > 0 ? (errors * 100.0 / total) : 0;

        return new MetricsSnapshot(
                barEventCount.get(),
                liquidationEventCount.get(),
                fundingRateEventCount.get(),
                openInterestEventCount.get(),
                signalCollector.getAllSignals().size(),
                getConnectorHealthSnapshot(),
                calculateAvg(eventLatencyBuffer, eventLatencyIndex.get()),
                calculateP99(eventLatencyBuffer, eventLatencyIndex.get()),
                calculateAvg(strategyLatencyBuffer, strategyLatencyIndex.get()),
                calculateP99(strategyLatencyBuffer, strategyLatencyIndex.get()),
                persistenceQueueDepth.get(),
                memoryUsedPct,
                errorRate
        );
    }

    /**
     * 手动注册连接器健康状态。
     */
    public void registerConnectorHealth(String name, ConnectorHealth health) {
        connectorHealthMap.put(name, health);
    }

    public long getBarEventCount() { return barEventCount.get(); }
    public long getLiquidationEventCount() { return liquidationEventCount.get(); }
    public long getFundingRateEventCount() { return fundingRateEventCount.get(); }
    public long getOpenInterestEventCount() { return openInterestEventCount.get(); }
    public long getPersistenceQueueDepth() { return persistenceQueueDepth.get(); }

    /**
     * 获取所有连接器的健康状态快照。
     */
    public Map<String, ConnectorHealth> getConnectorHealthSnapshot() {
        return Map.copyOf(connectorHealthMap);
    }
}
