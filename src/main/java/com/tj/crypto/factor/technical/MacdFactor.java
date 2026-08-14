package com.tj.crypto.factor.technical;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.FactorProperties;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.BarSlices;
import com.tj.crypto.factor.core.BarHistoryFactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * MACD 因子。
 * 返回 MACD 柱状图（MACD 线 - 信号线）。
 *
 * MACD 金叉：histogram 从负变正 -> 买入信号
 * MACD 死叉：histogram 从正变负 -> 卖出信号
 */
@Component
public class MacdFactor implements BarHistoryFactorCalculator {

    private final BarCache barCache;
    private final FactorProperties factorProperties;

    public MacdFactor(BarCache barCache, FactorProperties factorProperties) {
        this.barCache = barCache;
        this.factorProperties = factorProperties;
    }

    @Override
    public String name() {
        return "MACD_HIST";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        int slowPeriod = factorProperties.getMacdSlow();
        int signalPeriod = factorProperties.getMacdSignal();
        int requiredBars = slowPeriod + signalPeriod + 10;
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, requiredBars);
        return calculateFromBars(instrument, timeframe, bars);
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe, List<BarEvent> bars) {
        int requiredBars = factorProperties.getMacdSlow() + factorProperties.getMacdSignal() + 10;
        return calculateFromBars(instrument, timeframe,
                BarSlices.latestFinalized(bars, requiredBars));
    }

    @Override
    public Factor calculateAsOf(Instrument instrument, Timeframe timeframe, long asOfTimestamp) {
        int requiredBars = factorProperties.getMacdSlow() + factorProperties.getMacdSignal() + 10;
        return calculateFromBars(instrument, timeframe,
                barCache.getBarsAsOf(instrument, timeframe, asOfTimestamp, requiredBars));
    }

    private Factor calculateFromBars(Instrument instrument, Timeframe timeframe, List<BarEvent> bars) {
        int fastPeriod = factorProperties.getMacdFast();
        int slowPeriod = factorProperties.getMacdSlow();
        int signalPeriod = factorProperties.getMacdSignal();
        int requiredBars = slowPeriod + signalPeriod + 10;
        if (bars.size() < requiredBars) {
            return Factor.warmup(name());
        }

        BarSeries series = Ta4jBarSeriesConverter.toBarSeries(bars, instrument.symbol() + "_" + timeframe.getCode());
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macdLine = new MACDIndicator(closePrice, fastPeriod, slowPeriod);
        EMAIndicator signalLine = new EMAIndicator(macdLine, signalPeriod);

        int endIndex = series.getEndIndex();
        // histogram = MACD 线 - 信号线
        double histogram = macdLine.getValue(endIndex).doubleValue() - signalLine.getValue(endIndex).doubleValue();
        BigDecimal value = BigDecimal.valueOf(histogram);
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), value, timestamp);
    }
}
