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
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * EMA（指数移动平均线）因子。
 * 使用 TA4J 计算指定周期的收盘价指数移动平均。
 */
@Component
public class EmaFactor implements BarHistoryFactorCalculator {

    private final BarCache barCache;
    private final FactorProperties factorProperties;

    public EmaFactor(BarCache barCache, FactorProperties factorProperties) {
        this.barCache = barCache;
        this.factorProperties = factorProperties;
    }

    @Override
    public String name() {
        return "EMA";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        int period = factorProperties.getEmaPeriod();
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, period * 3);
        return calculateFromBars(instrument, timeframe, bars, period);
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe, List<BarEvent> bars) {
        int period = factorProperties.getEmaPeriod();
        return calculateFromBars(instrument, timeframe,
                BarSlices.latestFinalized(bars, period * 3), period);
    }

    @Override
    public Factor calculateAsOf(Instrument instrument, Timeframe timeframe, long asOfTimestamp) {
        int period = factorProperties.getEmaPeriod();
        return calculateFromBars(instrument, timeframe,
                barCache.getBarsAsOf(instrument, timeframe, asOfTimestamp, period * 3), period);
    }

    private Factor calculateFromBars(Instrument instrument, Timeframe timeframe,
                                     List<BarEvent> bars, int period) {
        if (bars.size() < period) {
            return Factor.warmup(name());
        }

        BarSeries series = Ta4jBarSeriesConverter.toBarSeries(bars, instrument.symbol() + "_" + timeframe.getCode());
        EMAIndicator ema = new EMAIndicator(new ClosePriceIndicator(series), period);

        int endIndex = series.getEndIndex();
        BigDecimal value = BigDecimal.valueOf(ema.getValue(endIndex).doubleValue());
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), value, timestamp);
    }
}
