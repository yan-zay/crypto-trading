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
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * RSI（相对强弱指标）因子。
 * 范围 0-100，通常 >70 超买，<30 超卖。
 */
@AllArgsConstructor
@Component
public class RsiFactor implements FactorCalculator {

    private final BarCache barCache;
    private final int period = 14;

    @Override
    public String name() {
        return "RSI_" + period;
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        int requiredBars = period * 3;
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, requiredBars);
        if (bars.size() < period + 1) {
            return Factor.warmup(name());
        }

        BarSeries series = Ta4jBarSeriesConverter.toBarSeries(bars, instrument.symbol() + "_" + timeframe.getCode());
        RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series), period);

        int endIndex = series.getEndIndex();
        BigDecimal value = BigDecimal.valueOf(rsi.getValue(endIndex).doubleValue());
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), value, timestamp);
    }
}
