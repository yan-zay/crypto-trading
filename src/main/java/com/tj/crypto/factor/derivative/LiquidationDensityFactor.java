package com.tj.crypto.factor.derivative;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 爆仓密度因子。
 * 计算单位时间内的爆仓总金额。
 *
 * 高值：大量爆仓发生（市场剧烈波动，可能是反转信号）
 * 低值：爆仓较少（市场平稳）
 */
@Slf4j
@Component
public class LiquidationDensityFactor implements FactorCalculator {

    private final MarketEventBus eventBus;
    private final ConcurrentHashMap<String, List<LiquidationRecord>> liqHistory = new ConcurrentHashMap<>();
    private static final long WINDOW_MILLIS = 5 * 60 * 1000; // 5 分钟窗口

    public LiquidationDensityFactor(MarketEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(LiquidationEvent.class, this::onLiquidation);
    }

    private void onLiquidation(LiquidationEvent event) {
        String key = event.instrument().symbol();
        liqHistory.compute(key, (k, v) -> {
            List<LiquidationRecord> list = v != null ? v : Collections.synchronizedList(new ArrayList<>());
            synchronized (list) {
                list.add(new LiquidationRecord(event.metadata().exchangeTimestamp(), event.quantityUsd()));
                long cutoff = System.currentTimeMillis() - WINDOW_MILLIS * 10;
                list.removeIf(r -> r.timestamp < cutoff);
            }
            return list;
        });
    }

    @Override
    public String name() {
        return "LIQUIDATION_DENSITY";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        List<LiquidationRecord> records = liqHistory.get(instrument.symbol());
        if (records == null) return Factor.warmup(name());
        synchronized (records) {
            if (records.isEmpty()) return Factor.warmup(name());
            long now = System.currentTimeMillis();
            long windowStart = now - WINDOW_MILLIS;
            BigDecimal totalInWindow = records.stream()
                    .filter(r -> r.timestamp >= windowStart)
                    .map(r -> r.amountUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return Factor.of(name(), totalInWindow, now);
        }
    }

    private record LiquidationRecord(long timestamp, BigDecimal amountUsd) {}
}
