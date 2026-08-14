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
import org.ta4j.core.indicators.bollinger.PercentBIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bollinger Bands %B 因子。
 * 使用 TA4J 0.17 的 PercentBIndicator 直接计算。
 * %B > 1 价格在上轨之上，%B < 0 价格在下轨之下，%B = 0.5 价格在中轨。
 */
@Component
public class BollingerBandFactor implements BarHistoryFactorCalculator {

    private final BarCache barCache;
    private final FactorProperties factorProperties;

    public BollingerBandFactor(BarCache barCache, FactorProperties factorProperties) {
        this.barCache = barCache;
        this.factorProperties = factorProperties;
    }

    @Override
    public String name() {
        return "BB_PCT_B";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        int period = factorProperties.getBbPeriod();
        int requiredBars = period + 10;
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, requiredBars);
        return calculateFromBars(instrument, timeframe, bars);
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe, List<BarEvent> bars) {
        int requiredBars = factorProperties.getBbPeriod() + 10;
        return calculateFromBars(instrument, timeframe,
                BarSlices.latestFinalized(bars, requiredBars));
    }

    @Override
    public Factor calculateAsOf(Instrument instrument, Timeframe timeframe, long asOfTimestamp) {
        int requiredBars = factorProperties.getBbPeriod() + 10;
        return calculateFromBars(instrument, timeframe,
                barCache.getBarsAsOf(instrument, timeframe, asOfTimestamp, requiredBars));
    }

    private Factor calculateFromBars(Instrument instrument, Timeframe timeframe, List<BarEvent> bars) {
        int period = factorProperties.getBbPeriod();
        double stdDevMultiplier = factorProperties.getBbStdDev();
        if (bars.size() < period) {
            return Factor.warmup(name());
        }

        BarSeries series = Ta4jBarSeriesConverter.toBarSeries(bars, instrument.symbol() + "_" + timeframe.getCode());
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // TA4J 0.17 PercentBIndicator(Indicator<Num>, int period, double stdDevMultiplier)
        PercentBIndicator pctB = new PercentBIndicator(closePrice, period, stdDevMultiplier);

        int endIndex = series.getEndIndex();
        BigDecimal value = BigDecimal.valueOf(pctB.getValue(endIndex).doubleValue());
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), value, timestamp);
    }
}
