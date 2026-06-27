package com.tj.crypto.admin.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 配置类型枚举。
 * 定义可版本化管理的配置类别。
 */
@Getter
@AllArgsConstructor
public enum ConfigType {

    CONNECTOR("connector", "连接器配置"),
    FACTOR("factor", "因子配置"),
    STRATEGY("strategy", "策略配置"),
    RISK("risk", "风控配置"),
    EXECUTION("execution", "执行配置"),
    ;

    private final String code;
    private final String displayName;
}
