package com.tj.crypto.strategy.impl;

import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.common.domain.MarketSeriesKey;
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
 * SuperTrend 趋势跟踪策略。
 * 监听 BarEvent（仅 closed=true），查询 SUPERTREND 因子，基于趋势方向变化生成信号。
 *
 * <p>信号逻辑：
 * <ul>
 *   <li>SuperTrend 值从 -1 变为 1（趋势转多）→ 买入信号</li>
 *   <li>SuperTrend 值从 1 变为 -1（趋势转空）→ 卖出信号</li>
 * </ul>
 *
 * <p>SuperTrend 是基于 ATR 的趋势跟踪指标，适合捕捉中长期趋势。
 * 与 MACD（趋势转折）、RSI（超买超卖）、Bollinger（波动率突破）互补。
 */
@Slf4j
@Component
public class SuperTrendStrategy implements Strategy {

    private static final String SUPERTREND_FACTOR = "SUPERTREND";

    /** 按交易对存储上一次 SuperTrend 值（+1 或 -1） */
    private final Map<MarketSeriesKey, BigDecimal> lastTrendMap = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "SuperTrend";
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

        Factor stFactor = context.getFactor(SUPERTREND_FACTOR, bar.instrument(), bar.timeframe());
        if (stFactor == null || !stFactor.isUsable()) {
            return null;
        }

        BigDecimal currentTrend = stFactor.value();
        MarketSeriesKey key = MarketSeriesKey.of(bar.instrument(), bar.timeframe());
        BigDecimal lastTrend = lastTrendMap.get(key);
        SignalEvent signal = null;

        if (lastTrend != null) {
            // 趋势从空转多：-1 → +1 → 买入
            if (lastTrend.compareTo(BigDecimal.ZERO) < 0
                    && currentTrend.compareTo(BigDecimal.ZERO) > 0) {
                signal = new SignalEvent(
                        name(), bar.instrument(), SignalType.BUY,
                        BigDecimal.valueOf(0.8),
                        String.format("SuperTrend 趋势转多: %s → %s (close=%.2f)",
                                lastTrend.intValue() > 0 ? "UP" : "DOWN",
                                "UP", bar.close()),
                        Map.of(SUPERTREND_FACTOR, currentTrend),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
            }
            // 趋势从多转空：+1 → -1 → 卖出
            else if (lastTrend.compareTo(BigDecimal.ZERO) > 0
                    && currentTrend.compareTo(BigDecimal.ZERO) < 0) {
                signal = new SignalEvent(
                        name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.valueOf(0.8),
                        String.format("SuperTrend 趋势转空: %s → %s (close=%.2f)",
                                "UP", "DOWN", bar.close()),
                        Map.of(SUPERTREND_FACTOR, currentTrend),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
            }
        }

        lastTrendMap.put(key, currentTrend);
        return signal;
    }
}
