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
import org.ta4j.core.indicators.ATRIndicator;

import java.math.BigDecimal;
import java.util.List;

/**
 * ATR（平均真实波幅）因子。
 * 使用 TA4J 的 ATRIndicator 计算指定周期的平均真实波幅。
 *
 * ATR 衡量市场波动性，值越大表示波动越剧烈。
 * 常用于止损设置、仓位管理和波动率过滤。
 *
 * 计算逻辑：
 * 1. True Range = max(High-Low, |High-PrevClose|, |Low-PrevClose|)
 * 2. ATR = SMA(True Range, period)
 */
@Component
public class AtrFactor implements FactorCalculator {

    private final BarCache barCache;
    private final int period;

    public AtrFactor(BarCache barCache, FactorProperties factorProperties) {
        this.barCache = barCache;
        this.period = factorProperties.getAtrPeriod();
    }

    @Override
    public String name() {
        return "ATR_" + period;
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        // ATR 需要 period + 1 根 bar（因为需要前一根 bar 的收盘价计算 True Range）
        int requiredBars = period + 10;
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, requiredBars);
        if (bars.size() < period + 1) {
            return Factor.warmup(name());
        }

        BarSeries series = Ta4jBarSeriesConverter.toBarSeries(bars, instrument.symbol() + "_" + timeframe.getCode());
        ATRIndicator atr = new ATRIndicator(series, period);

        int endIndex = series.getEndIndex();
        BigDecimal value = BigDecimal.valueOf(atr.getValue(endIndex).doubleValue());
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), value, timestamp);
    }
}
