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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统指标收集器。
 * 收集事件吞吐量、信号数量、连接状态等基础指标。
 *
 * 设计决策：
 * - 使用 AtomicLong 计数器，无锁高性能
 * - 定时输出到日志（后续可接入 Micrometer/Prometheus）
 * - 轻量级，不影响主流程
 */
@Slf4j
@Component
public class SystemMetrics {

    private final MarketEventBus eventBus;
    private final SignalCollector signalCollector;
    private final List<MarketDataConnector> connectors;

    private final AtomicLong barEventCount = new AtomicLong(0);
    private final AtomicLong liquidationEventCount = new AtomicLong(0);
    private final AtomicLong fundingRateEventCount = new AtomicLong(0);
    private final AtomicLong openInterestEventCount = new AtomicLong(0);

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
        eventBus.subscribe(BarEvent.class, e -> barEventCount.incrementAndGet());
        eventBus.subscribe(LiquidationEvent.class, e -> liquidationEventCount.incrementAndGet());
        eventBus.subscribe(FundingRateEvent.class, e -> fundingRateEventCount.incrementAndGet());
        eventBus.subscribe(OpenInterestEvent.class, e -> openInterestEventCount.incrementAndGet());
        log.info("SystemMetrics initialized with {} connector(s)", connectors.size());
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

        log.info("[METRICS] Events/min - Bar: {}, Liquidation: {}, FundingRate: {}, OpenInterest: {} | Total signals: {}",
                bars, liquidations, fundingRates, openInterests,
                signalCollector.getAllSignals().size());

        reportConnectorHealth();
    }

    /**
     * 报告所有连接器的健康状态。
     */
    private void reportConnectorHealth() {
        // 报告 MarketDataConnector 实现的健康状态
        for (MarketDataConnector connector : connectors) {
            ConnectorHealth health = connector.health();
            if (!health.connected()) {
                log.warn("[METRICS] Connector disconnected: messagesReceived={}, reconnectCount={}, lastError={}",
                        health.messagesReceived(), health.reconnectCount(), health.lastError());
            }
        }

        // 报告手动注册的连接状态
        connectorHealthMap.forEach((name, health) -> {
            if (!health.connected()) {
                log.warn("[METRICS] {} disconnected: messagesReceived={}, reconnectCount={}, lastError={}",
                        name, health.messagesReceived(), health.reconnectCount(), health.lastError());
            }
        });
    }

    /**
     * 手动注册连接器健康状态。
     * 用于非 MarketDataConnector 实现的组件（如 CoinglassWebSocketClient）。
     *
     * @param name   连接器名称
     * @param health 健康状态
     */
    public void registerConnectorHealth(String name, ConnectorHealth health) {
        connectorHealthMap.put(name, health);
    }

    public long getBarEventCount() { return barEventCount.get(); }
    public long getLiquidationEventCount() { return liquidationEventCount.get(); }
    public long getFundingRateEventCount() { return fundingRateEventCount.get(); }
    public long getOpenInterestEventCount() { return openInterestEventCount.get(); }

    /**
     * 获取所有连接器的健康状态快照。
     */
    public Map<String, ConnectorHealth> getConnectorHealthSnapshot() {
        return Map.copyOf(connectorHealthMap);
    }
}
