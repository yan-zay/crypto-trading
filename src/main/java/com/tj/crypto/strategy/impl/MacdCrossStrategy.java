package com.tj.crypto.strategy.impl;

import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.common.domain.MarketSeriesKey;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MACD 金叉/死叉策略（示例）。
 * 监听 BarEvent，查询 MACD 因子，金叉买入、死叉卖出。
 *
 * 展示如何使用因子系统：
 * - 通过 StrategyContext 查询 MACD_HIST 因子
 * - 当 MACD 柱状图从负变正 → 金叉 → 买入信号
 * - 当 MACD 柱状图从正变负 → 死叉 → 卖出信号
 */
@Slf4j
@Component
public class MacdCrossStrategy implements Strategy {

    /** 按交易对存储上一次 MACD 柱状图值（避免跨交易对状态污染） */
    private final Map<MarketSeriesKey, BigDecimal> lastHistogramMap = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "MacdCross";
    }

    @Override
    public Set<Class<? extends MarketEvent>> listenedEvents() {
        return Set.of(BarEvent.class);
    }

    @Override
    public SignalEvent onEvent(MarketEvent event, StrategyContext context) {
        if (!(event instanceof BarEvent bar) || !bar.closed()) {
            return null;
        }

        Factor macdFactor = context.getFactor("MACD_HIST", bar.instrument(), bar.timeframe());
        if (macdFactor == null || !macdFactor.isUsable()) {
            return null;
        }

        BigDecimal currentHistogram = macdFactor.value();
        MarketSeriesKey key = MarketSeriesKey.of(bar.instrument(), bar.timeframe());
        BigDecimal lastHistogram = lastHistogramMap.get(key);
        SignalEvent signal = null;

        if (lastHistogram != null) {
            if (lastHistogram.compareTo(BigDecimal.ZERO) < 0 && currentHistogram.compareTo(BigDecimal.ZERO) >= 0) {
                signal = new SignalEvent(name(), bar.instrument(), SignalType.BUY,
                        BigDecimal.valueOf(0.7),
                        String.format("MACD 金叉: hist %.4f → %.4f", lastHistogram, currentHistogram),
                        Map.of("MACD_HIST", currentHistogram),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
            } else if (lastHistogram.compareTo(BigDecimal.ZERO) >= 0 && currentHistogram.compareTo(BigDecimal.ZERO) < 0) {
                signal = new SignalEvent(name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.valueOf(0.7),
                        String.format("MACD 死叉: hist %.4f → %.4f", lastHistogram, currentHistogram),
                        Map.of("MACD_HIST", currentHistogram),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
            }
        }

        lastHistogramMap.put(key, currentHistogram);
        return signal;
    }
}
