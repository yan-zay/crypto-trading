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
public class MacdFactor implements FactorCalculator {

    private final BarCache barCache;
    private final int fastPeriod;
    private final int slowPeriod;
    private final int signalPeriod;

    public MacdFactor(BarCache barCache, FactorProperties factorProperties) {
        this.barCache = barCache;
        this.fastPeriod = factorProperties.getMacdFast();
        this.slowPeriod = factorProperties.getMacdSlow();
        this.signalPeriod = factorProperties.getMacdSignal();
    }

    @Override
    public String name() {
        return "MACD_HIST";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        int requiredBars = slowPeriod + signalPeriod + 10;
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, requiredBars);
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
