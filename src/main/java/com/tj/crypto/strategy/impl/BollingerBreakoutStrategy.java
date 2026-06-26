package com.tj.crypto.strategy.impl;

import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bollinger Band 突破策略。
 * 监听 BarEvent，查询 BB_PCT_B 因子，基于 %B 指标的零轴突破生成信号。
 *
 * <p>信号逻辑：
 * <ul>
 *   <li>买入：%B 从下方突破 0（价格从下轨反弹，意味着从超卖区域回升）</li>
 *   <li>卖出：%B 从上方跌破 1（价格从上轨回落，意味着从超买区域回落）</li>
 * </ul>
 *
 * <p>与 MACD 和 RSI 策略互补：MACD 捕捉趋势转折，RSI 捕捉超买超卖，Bollinger 捕捉波动率突破。
 */
@Slf4j
public class BollingerBreakoutStrategy implements Strategy {

    private static final String BB_FACTOR_NAME = "BB_PCT_B";

    /** 按交易对存储上一次 %B 值 */
    private final Map<String, BigDecimal> lastPctBMap = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "BollingerBreakout";
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

        Factor bbFactor = context.getFactor(BB_FACTOR_NAME, bar.instrument(), bar.timeframe());
        if (bbFactor == null || !bbFactor.isUsable()) {
            return null;
        }

        BigDecimal currentPctB = bbFactor.value();
        String key = bar.instrument().symbol();
        BigDecimal lastPctB = lastPctBMap.get(key);
        SignalEvent signal = null;

        if (lastPctB != null) {
            // %B 从下方突破 0 → 价格从下轨反弹 → 买入信号
            if (lastPctB.compareTo(BigDecimal.ZERO) < 0
                    && currentPctB.compareTo(BigDecimal.ZERO) >= 0) {
                signal = new SignalEvent(
                        name(), bar.instrument(), SignalType.BUY,
                        BigDecimal.valueOf(0.7),
                        String.format("BB 下轨反弹: %%B %.4f → %.4f", lastPctB, currentPctB),
                        Map.of(BB_FACTOR_NAME, currentPctB),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
            }
            // %B 从上方跌破 1 → 价格从上轨回落 → 卖出信号
            else if (lastPctB.compareTo(BigDecimal.ONE) >= 0
                    && currentPctB.compareTo(BigDecimal.ONE) < 0) {
                signal = new SignalEvent(
                        name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.valueOf(0.7),
                        String.format("BB 上轨回落: %%B %.4f → %.4f", lastPctB, currentPctB),
                        Map.of(BB_FACTOR_NAME, currentPctB),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
            }
        }

        lastPctBMap.put(key, currentPctB);
        return signal;
    }
}
