package com.tj.crypto.factor.technical;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.FactorProperties;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.adx.ADXIndicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADX（平均趋向指数）因子。
 * 使用 TA4J 的 ADXIndicator 计算指定周期的趋势强度。
 *
 * ADX 范围 0-100，衡量趋势强度（不区分方向）：
 * - ADX < 20：无明显趋势（震荡市）
 * - ADX 20-40：趋势发展中
 * - ADX > 40：强趋势
 * - ADX > 60：非常强的趋势
 *
 * 通常配合 +DI/-DI 判断趋势方向，本因子仅返回 ADX 值。
 */
@Component
public class AdxFactor implements FactorCalculator {

    private final BarCache barCache;
    private final int period;

    public AdxFactor(BarCache barCache, FactorProperties factorProperties) {
        this.barCache = barCache;
        this.period = factorProperties.getAdxPeriod();
    }

    @Override
    public String name() {
        return "ADX_" + period;
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        // ADX 需要足够的 bar 来计算 +DI/-DI 和 ADX 本身
        int requiredBars = period * 3 + 10;
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, requiredBars);
        if (bars.size() < period * 2 + 1) {
            return Factor.warmup(name());
        }

        BarSeries series = Ta4jBarSeriesConverter.toBarSeries(bars, instrument.symbol() + "_" + timeframe.getCode());
        ADXIndicator adx = new ADXIndicator(series, period);

        int endIndex = series.getEndIndex();
        BigDecimal value = BigDecimal.valueOf(adx.getValue(endIndex).doubleValue());
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), value, timestamp);
    }
}
