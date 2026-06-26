package com.tj.crypto.factor.technical;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP（成交量加权平均价格）因子。
 * 计算当日的成交量加权平均价格。
 *
 * VWAP = sum(典型价格 * 成交量) / sum(成交量)
 * 典型价格 = (High + Low + Close) / 3
 *
 * VWAP 是机构交易者常用基准：
 * - 价格 > VWAP：多头占优，买方力量强
 * - 价格 < VWAP：空头占优，卖方力量强
 *
 * 注意：当前实现基于缓存的 bar 数据计算，不区分交易日。
 * 对于日内策略，应配合 D1 或更小周期使用。
 */
@Component
public class VwapFactor implements FactorCalculator {

    private final BarCache barCache;

    public VwapFactor(BarCache barCache) {
        this.barCache = barCache;
    }

    @Override
    public String name() {
        return "VWAP";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        // 获取最近的 bar 数据（用于近似当日 VWAP）
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, 500);
        if (bars.isEmpty()) {
            return Factor.warmup(name());
        }

        // 找到当天第一根 bar 的索引（基于时间戳判断是否同一天）
        long latestTimestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();
        long dayStart = latestTimestamp - (latestTimestamp % 86_400_000L);

        BigDecimal cumulativeTypicalVolume = BigDecimal.ZERO;
        BigDecimal cumulativeVolume = BigDecimal.ZERO;
        boolean hasBarsToday = false;

        for (BarEvent bar : bars) {
            long barTimestamp = bar.metadata().exchangeTimestamp();
            if (barTimestamp < dayStart) {
                continue;
            }
            hasBarsToday = true;

            // 典型价格 = (High + Low + Close) / 3
            BigDecimal typicalPrice = bar.high()
                    .add(bar.low())
                    .add(bar.close())
                    .divide(BigDecimal.valueOf(3), 10, RoundingMode.HALF_UP);

            BigDecimal volume = bar.volume();
            cumulativeTypicalVolume = cumulativeTypicalVolume.add(typicalPrice.multiply(volume));
            cumulativeVolume = cumulativeVolume.add(volume);
        }

        if (!hasBarsToday || cumulativeVolume.compareTo(BigDecimal.ZERO) == 0) {
            return Factor.warmup(name());
        }

        BigDecimal vwap = cumulativeTypicalVolume
                .divide(cumulativeVolume, 6, RoundingMode.HALF_UP);
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), vwap, timestamp);
    }
}
