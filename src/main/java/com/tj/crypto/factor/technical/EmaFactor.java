package com.tj.crypto.factor.technical;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import lombok.AllArgsConstructor;
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
@AllArgsConstructor
@Component
public class EmaFactor implements FactorCalculator {

    private final BarCache barCache;
    private final int period = 20;

    @Override
    public String name() {
        return "EMA_" + period;
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, period * 3);
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
