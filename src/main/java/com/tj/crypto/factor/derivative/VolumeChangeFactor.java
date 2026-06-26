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
 * 成交量变化率因子。
 * 计算最近一根 bar 相对于前一根 bar 的成交量变化百分比。
 *
 * 计算公式：
 * volumeChangePct = (currentVolume - previousVolume) / previousVolume * 100
 *
 * 值解读：
 * - 正值：成交量放大（市场活跃度增加）
 * - 负值：成交量萎缩（市场活跃度降低）
 * - 大幅正值配合价格上涨：突破信号
 * - 大幅正值配合价格下跌：恐慌抛售
 *
 * 成交量变化是判断趋势可靠性的重要指标：
 * - 上涨 + 放量：趋势可靠
 * - 上涨 + 缩量：动能不足，可能回调
 * - 下跌 + 放量：恐慌抛售
 * - 下跌 + 缩量：抛压减弱，可能企稳
 */
@Component
public class VolumeChangeFactor implements FactorCalculator {

    private final BarCache barCache;

    public VolumeChangeFactor(BarCache barCache) {
        this.barCache = barCache;
    }

    @Override
    public String name() {
        return "VOLUME_CHANGE_PCT";
    }

    @Override
    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, 3);
        if (bars.size() < 2) {
            return Factor.warmup(name());
        }

        BarEvent current = bars.get(bars.size() - 1);
        BarEvent previous = bars.get(bars.size() - 2);

        BigDecimal previousVolume = previous.volume();
        if (previousVolume.compareTo(BigDecimal.ZERO) == 0) {
            // 前一根 bar 成交量为零，无法计算变化率
            return Factor.of(name(), BigDecimal.ZERO, current.metadata().exchangeTimestamp());
        }

        BigDecimal changePct = current.volume()
                .subtract(previousVolume)
                .divide(previousVolume, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        long timestamp = current.metadata().exchangeTimestamp();
        return Factor.of(name(), changePct, timestamp);
    }
}
