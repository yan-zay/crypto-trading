package com.tj.crypto.observability;

import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalCollector;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统指标收集器。
 * 收集事件吞吐量、信号数量等基础指标。
 *
 * 设计决策：
 * - 使用 AtomicLong 计数器，无锁高性能
 * - 定时输出到日志（后续可接入 Micrometer/Prometheus）
 * - 轻量级，不影响主流程
 */
@Slf4j
@Component
@AllArgsConstructor
public class SystemMetrics {

    private final MarketEventBus eventBus;
    private final SignalCollector signalCollector;

    private final AtomicLong barEventCount = new AtomicLong(0);
    private final AtomicLong liquidationEventCount = new AtomicLong(0);

    @PostConstruct
    public void init() {
        eventBus.subscribe(BarEvent.class, e -> barEventCount.incrementAndGet());
        log.info("SystemMetrics initialized");
    }

    /**
     * 每分钟输出一次指标。
     */
    @Scheduled(fixedRate = 60000)
    public void reportMetrics() {
        long bars = barEventCount.getAndSet(0);
        log.info("[METRICS] BarEvents/min: {}, Total signals: {}",
                bars, signalCollector.getSignals("*").size());
    }

    public long getBarEventCount() { return barEventCount.get(); }
}
