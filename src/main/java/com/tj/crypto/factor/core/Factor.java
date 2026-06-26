package com.tj.crypto.factor.core;

import java.math.BigDecimal;

/**
 * 因子值，不可变值对象。
 * 表示某个时间点的因子计算结果。
 *
 * @param name      因子名称，如 "SMA_20", "RSI_14", "MACD_HIST"
 * @param value     因子值
 * @param timestamp 计算时间戳（毫秒）
 * @param quality   因子质量
 */
public record Factor(
        String name,
        BigDecimal value,
        long timestamp,
        FactorQuality quality
) {
    /**
     * 创建预热期因子。
     */
    public static Factor warmup(String name) {
        return new Factor(name, BigDecimal.ZERO, System.currentTimeMillis(), FactorQuality.WARMUP);
    }

    /**
     * 创建就绪因子。
     */
    public static Factor of(String name, BigDecimal value, long timestamp) {
        return new Factor(name, value, timestamp, FactorQuality.READY);
    }

    /**
     * 因子是否可用（非预热期）。
     */
    public boolean isUsable() {
        return quality == FactorQuality.READY;
    }
}
