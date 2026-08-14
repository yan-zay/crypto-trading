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
 * RSI 超买超卖策略。
 * 监听 BarEvent，查询 RSI 因子，超卖区域买入、超买区域卖出。
 *
 * <p>信号逻辑：
 * <ul>
 *   <li>RSI 从超卖区域（&lt;30）回升到 30 以上 → 买入信号</li>
 *   <li>RSI 从超买区域（&gt;70）回落到 70 以下 → 卖出信号</li>
 * </ul>
 *
 * <p>与 MACD 策略互补：MACD 捕捉趋势转折，RSI 捕捉超买超卖。
 */
@Slf4j
@Component
public class RsiCrossStrategy implements Strategy {

    private static final BigDecimal OVERSOLD_THRESHOLD = BigDecimal.valueOf(30);
    private static final BigDecimal OVERBOUGHT_THRESHOLD = BigDecimal.valueOf(70);

    /** 按交易对存储上一次 RSI 值 */
    private final Map<MarketSeriesKey, BigDecimal> lastRsiMap = new ConcurrentHashMap<>();

    private final String rsiFactorName;

    /**
     * 使用默认 RSI 周期（14）。
     */
    public RsiCrossStrategy() {
        this("RSI");
    }

    /**
     * 使用自定义 RSI 因子名称。
     *
     * @param rsiFactorName RSI 因子名称（如 "RSI"、"RSI_7"）
     */
    public RsiCrossStrategy(String rsiFactorName) {
        this.rsiFactorName = rsiFactorName;
    }

    @Override
    public String name() {
        return "RsiCross";
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

        Factor rsiFactor = context.getFactor(rsiFactorName, bar.instrument(), bar.timeframe());
        if (rsiFactor == null || !rsiFactor.isUsable()) {
            return null;
        }

        BigDecimal currentRsi = rsiFactor.value();
        MarketSeriesKey key = MarketSeriesKey.of(bar.instrument(), bar.timeframe());
        BigDecimal lastRsi = lastRsiMap.get(key);
        SignalEvent signal = null;

        if (lastRsi != null) {
            // RSI 从超卖区域回升 → 买入
            if (lastRsi.compareTo(OVERSOLD_THRESHOLD) < 0
                    && currentRsi.compareTo(OVERSOLD_THRESHOLD) >= 0) {
                signal = new SignalEvent(
                        name(), bar.instrument(), SignalType.BUY,
                        BigDecimal.valueOf(0.7),
                        String.format("RSI 超卖回升: %.2f → %.2f", lastRsi, currentRsi),
                        Map.of(rsiFactorName, currentRsi),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
            }
            // RSI 从超买区域回落 → 卖出
            else if (lastRsi.compareTo(OVERBOUGHT_THRESHOLD) >= 0
                    && currentRsi.compareTo(OVERBOUGHT_THRESHOLD) < 0) {
                signal = new SignalEvent(
                        name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.valueOf(0.7),
                        String.format("RSI 超买回落: %.2f → %.2f", lastRsi, currentRsi),
                        Map.of(rsiFactorName, currentRsi),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
            }
        }

        lastRsiMap.put(key, currentRsi);
        return signal;
    }
}
