package com.tj.crypto.observability.alert;

/**
 * 告警规则，不可变值对象。
 *
 * @param name      规则名称（唯一标识）
 * @param condition 条件类型（如 EVENT_THROUGHPUT_DROP, DISCONNECTED, HIGH_MEMORY, HIGH_ERROR_RATE）
 * @param threshold 阈值
 * @param severity  严重级别
 * @param enabled   是否启用
 */
public record AlertRule(
        String name,
        String condition,
        double threshold,
        Severity severity,
        boolean enabled
) {

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }
}
