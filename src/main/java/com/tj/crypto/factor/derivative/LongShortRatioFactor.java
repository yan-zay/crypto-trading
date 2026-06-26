package com.tj.crypto.factor.derivative;

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
 * 多空比因子（近似值）。
 * 通过成交量变化趋势近似推算多空力量对比。
 *
 * 计算逻辑：
 * 1. 获取最近 N 根 bar 的成交量序列
 * 2. 计算上涨 bar 的平均成交量（多头成交量）
 * 3. 计算下跌 bar 的平均成交量（空头成交量）
 * 4. 多空比 = 多头平均成交量 / 空头平均成交量
 *
 * 值解读：
 * - > 1.0：多头力量占优（上涨时成交量更大）
 * - < 1.0：空头力量占优（下跌时成交量更大）
 * - = 1.0：多空平衡
 *
 * 注意：这是基于成交量的近似估算，非真实多空持仓比。
 * 真实多空比需要交易所提供的账户级数据。
 */
@Component
public class LongShortRatioFactor implements FactorCalculator {

    private final BarCache barCache;
    private static final int LOOKBACK_BARS = 20;

    public LongShortRatioFactor(BarCache barCache) {
        this.barCache = barCache;
    }

    @Override
    public String name() {
        return "LONG_SHORT_RATIO";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, LOOKBACK_BARS);
        if (bars.size() < 5) {
            return Factor.warmup(name());
        }

        BigDecimal bullVolumeSum = BigDecimal.ZERO;
        BigDecimal bearVolumeSum = BigDecimal.ZERO;
        int bullCount = 0;
        int bearCount = 0;

        for (int i = 1; i < bars.size(); i++) {
            BarEvent current = bars.get(i);
            BarEvent previous = bars.get(i - 1);
            BigDecimal volume = current.volume();

            if (current.close().compareTo(previous.close()) > 0) {
                // 上涨 bar：计入多头成交量
                bullVolumeSum = bullVolumeSum.add(volume);
                bullCount++;
            } else if (current.close().compareTo(previous.close()) < 0) {
                // 下跌 bar：计入空头成交量
                bearVolumeSum = bearVolumeSum.add(volume);
                bearCount++;
            }
            // 收盘价相等的 bar 不计入
        }

        // 如果只有一种方向的 bar，返回极端值
        if (bullCount == 0 && bearCount == 0) {
            return Factor.of(name(), BigDecimal.ONE, System.currentTimeMillis());
        }
        if (bearCount == 0) {
            return Factor.of(name(), BigDecimal.TEN, System.currentTimeMillis());
        }
        if (bullCount == 0) {
            return Factor.of(name(), BigDecimal.valueOf(0.1), System.currentTimeMillis());
        }

        BigDecimal avgBullVolume = bullVolumeSum.divide(BigDecimal.valueOf(bullCount), 10, RoundingMode.HALF_UP);
        BigDecimal avgBearVolume = bearVolumeSum.divide(BigDecimal.valueOf(bearCount), 10, RoundingMode.HALF_UP);

        BigDecimal ratio = avgBullVolume.divide(avgBearVolume, 6, RoundingMode.HALF_UP);
        long timestamp = bars.get(bars.size() - 1).metadata().exchangeTimestamp();

        return Factor.of(name(), ratio, timestamp);
    }
}
