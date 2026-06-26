package com.tj.crypto.factor.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 因子质量枚举。
 * 标识因子值的可靠程度。
 */
@Getter
@AllArgsConstructor
public enum FactorQuality {
    /** 预热期：数据不足，计算结果不可靠 */
    WARMUP("warmup", "预热期"),
    /** 就绪：数据充足，结果可靠 */
    READY("ready", "就绪"),
    /** 过期：数据陈旧，可能不准确 */
    STALE("stale", "过期"),
    ;

    private final String code;
    private final String displayName;
}
