package com.tj.crypto.strategy.impl;

import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketSeriesKey;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ATR 追踪止损策略。
 * 监听 BarEvent，使用 ATR 因子计算动态止损距离，当价格跌破追踪止损线时卖出。
 *
 * <p>信号逻辑：
 * <ul>
 *   <li>追踪止损线 = 最高价 - 2 * ATR</li>
 *   <li>当收盘价跌破追踪止损线 → 卖出信号</li>
 *   <li>最高价随价格上涨持续更新（只升不降），实现追踪效果</li>
 * </ul>
 *
 * <p>适用场景：配合趋势跟踪策略（如 SuperTrend、MACD）使用，在趋势反转时及时止损。
 */
@Slf4j
@Component
public class AtrTrailingStopStrategy implements Strategy {

    private static final String ATR_FACTOR_NAME = "ATR";
    private static final BigDecimal ATR_MULTIPLIER = BigDecimal.valueOf(2);

    /** 按交易对存储追踪止损状态 */
    private final Map<MarketSeriesKey, TrailingStopState> stateMap = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "AtrTrailingStop";
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

        Factor atrFactor = context.getFactor(ATR_FACTOR_NAME, bar.instrument(), bar.timeframe());
        if (atrFactor == null || !atrFactor.isUsable()) {
            return null;
        }

        BigDecimal currentAtr = atrFactor.value();
        BigDecimal closePrice = bar.close();
        BigDecimal highPrice = bar.high();
        MarketSeriesKey key = MarketSeriesKey.of(bar.instrument(), bar.timeframe());

        TrailingStopState state = stateMap.get(key);
        SignalEvent signal = null;

        if (state != null) {
            // 更新最高价（只升不降）
            BigDecimal newHighest = highPrice.max(state.highestHigh());
            BigDecimal stopLine = newHighest.subtract(ATR_MULTIPLIER.multiply(currentAtr))
                    .setScale(8, RoundingMode.HALF_UP);

            // 当前持有追踪中，检查是否触发止损
            if (state.isTracking() && closePrice.compareTo(stopLine) < 0) {
                signal = new SignalEvent(
                        name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.valueOf(0.9),
                        String.format("ATR 追踪止损触发: close=%.2f < stop=%.2f (high=%.2f, ATR=%.2f)",
                                closePrice, stopLine, newHighest, currentAtr),
                        Map.of(ATR_FACTOR_NAME, currentAtr,
                                "STOP_LINE", stopLine,
                                "HIGHEST_HIGH", newHighest),
                        bar.metadata().exchangeTimestamp());
                log.info("[SIGNAL] {}: {}", signal.strategyName(), signal.reason());
                // 止损后重置追踪状态
                stateMap.put(key, new TrailingStopState(BigDecimal.ZERO, false));
                return signal;
            }

            // 更新追踪状态
            stateMap.put(key, new TrailingStopState(newHighest, true));
        } else {
            // 首次收到数据，初始化追踪状态（开始追踪）
            stateMap.put(key, new TrailingStopState(highPrice, true));
        }

        return signal;
    }

    /**
     * 启动追踪（当外部策略产生买入信号时调用）。
     *
     * @param symbol      交易对符号
     * @param entryHigh   入场时的最高价
     */
    public void startTracking(Instrument instrument, Timeframe timeframe, BigDecimal entryHigh) {
        stateMap.put(MarketSeriesKey.of(instrument, timeframe), new TrailingStopState(entryHigh, true));
        log.info("[AtrTrailingStop] Start tracking {} {}: highestHigh={}",
                instrument.id(), timeframe.getCode(), entryHigh);
    }

    /**
     * 停止追踪。
     *
     * @param symbol 交易对符号
     */
    public void stopTracking(Instrument instrument, Timeframe timeframe) {
        stateMap.put(MarketSeriesKey.of(instrument, timeframe),
                new TrailingStopState(BigDecimal.ZERO, false));
        log.info("[AtrTrailingStop] Stop tracking {} {}", instrument.id(), timeframe.getCode());
    }

    /**
     * 追踪止损状态。
     *
     * @param highestHigh 历史最高价（追踪窗口内）
     * @param isTracking  是否正在追踪
     */
    private record TrailingStopState(BigDecimal highestHigh, boolean isTracking) {}
}
