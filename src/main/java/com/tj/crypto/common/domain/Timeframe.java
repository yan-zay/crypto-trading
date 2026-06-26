package com.tj.crypto.common.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 时间周期枚举。
 * 用于 K 线数据的时间粒度。
 */
@Getter
@AllArgsConstructor
public enum Timeframe {
    M1("1m", "1分钟", 60_000L),
    M5("5m", "5分钟", 300_000L),
    M15("15m", "15分钟", 900_000L),
    M30("30m", "30分钟", 1_800_000L),
    H1("1h", "1小时", 3_600_000L),
    H4("4h", "4小时", 14_400_000L),
    D1("1d", "1天", 86_400_000L),
    ;

    private final String code;
    private final String displayName;
    private final long millis;

    /**
     * 从 Binance 风格的 interval 字符串解析 Timeframe。
     * 如 "1m" → M1, "5m" → M5, "1h" → H1。
     */
    public static Timeframe fromCode(String code) {
        for (Timeframe tf : values()) {
            if (tf.code.equals(code)) {
                return tf;
            }
        }
        throw new IllegalArgumentException("Unknown timeframe code: " + code);
    }
}
