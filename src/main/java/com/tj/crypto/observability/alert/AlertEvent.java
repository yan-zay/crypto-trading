package com.tj.crypto.observability.alert;

/**
 * 告警事件，不可变值对象。
 *
 * @param ruleName  触发的规则名称
 * @param severity  严重级别
 * @param message   告警描述
 * @param timestamp 触发时间戳（毫秒）
 * @param resolved  是否已解除
 */
public record AlertEvent(
        String ruleName,
        AlertRule.Severity severity,
        String message,
        long timestamp,
        boolean resolved
) {
}
