package com.tj.crypto.factor.technical;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.BarSlices;
import com.tj.crypto.factor.core.BarHistoryFactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * SuperTrend 因子。
 * 基于 ATR 计算 SuperTrend 趋势指标。
 *
 * SuperTrend 是一个趋势跟踪指标，结合价格和波动率（ATR）判断趋势方向：
 * - 返回 1：上升趋势（价格在 SuperTrend 线之上）
 * - 返回 -1：下降趋势（价格在 SuperTrend 线之下）
 *
 * 计算逻辑：
 * 1. HL2 = (High + Low) / 2
 * 2. Basic Upper Band = HL2 + multiplier * ATR
 * 3. Basic Lower Band = HL2 - multiplier * ATR
 * 4. Final Upper Band = 当前 Basic Upper Band 或前一个 Final Upper Band（取较小值，如果前收盘价 < 前 Final Upper Band）
 * 5. Final Lower Band = 当前 Basic Lower Band 或前一个 Final Lower Band（取较大值，如果前收盘价 > 前 Final Lower Band）
 * 6. SuperTrend = 收盘价 <= Final Lower Band ? Final Upper Band : Final Lower Band
 * 7. 趋势 = 收盘价 > SuperTrend ? 1 : -1
 *
 * 默认参数：period=10, multiplier=3.0
 */
@Component
public class SuperTrendFactor implements BarHistoryFactorCalculator {

    private final BarCache barCache;
    private final int period;
    private final double multiplier;

    public SuperTrendFactor(BarCache barCache) {
        this.barCache = barCache;
        this.period = 10;
        this.multiplier = 3.0;
    }

    @Override
    public String name() {
        return "SUPERTREND";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        int requiredBars = period + 10;
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, requiredBars);
        return calculateFromBars(instrument, timeframe, bars);
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe, List<BarEvent> bars) {
        return calculateFromBars(instrument, timeframe,
                BarSlices.latestFinalized(bars, period + 10));
    }

    @Override
    public Factor calculateAsOf(Instrument instrument, Timeframe timeframe, long asOfTimestamp) {
        return calculateFromBars(instrument, timeframe,
                barCache.getBarsAsOf(instrument, timeframe, asOfTimestamp, period + 10));
    }

    private Factor calculateFromBars(Instrument instrument, Timeframe timeframe, List<BarEvent> bars) {
        if (bars.size() < period + 1) {
            return Factor.warmup(name());
        }

        BarSeries series = Ta4jBarSeriesConverter.toBarSeries(bars, instrument.symbol() + "_" + timeframe.getCode());
        ATRIndicator atr = new ATRIndicator(series, period);

        int endIndex = series.getEndIndex();

        // 计算 SuperTrend
        double finalUpperBand = 0;
        double finalLowerBand = 0;
        int trend = 1;

        for (int i = 1; i <= endIndex; i++) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            double low = series.getBar(i).getLowPrice().doubleValue();
            double close = series.getBar(i).getClosePrice().doubleValue();
            double prevClose = series.getBar(i - 1).getClosePrice().doubleValue();
            double atrValue = atr.getValue(i).doubleValue();

            double hl2 = (high + low) / 2.0;
            double basicUpperBand = hl2 + multiplier * atrValue;
            double basicLowerBand = hl2 - multiplier * atrValue;

            // Final Upper Band
            if (i == 1) {
                finalUpperBand = basicUpperBand;
            } else {
                if (basicUpperBand < finalUpperBand || prevClose > finalUpperBand) {
                    finalUpperBand = basicUpperBand;
                }
            }

            // Final Lower Band
            if (i == 1) {
                finalLowerBand = basicLowerBand;
            } else {
                if (basicLowerBand > finalLowerBand || prevClose < finalLowerBand) {
                    finalLowerBand = basicLowerBand;
                }
            }

            // 趋势判断
            if (i == 1) {
                trend = close > finalUpperBand ? 1 : -1;
            } else {
                if (trend == 1) {
                    if (close < finalLowerBand) {
                        trend = -1;
                    }
                } else {
                    if (close > finalUpperBand) {
                        trend = 1;
                    }
                }
            }
        }

        BigDecimal value = BigDecimal.valueOf(trend);
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), value, timestamp);
    }
}
