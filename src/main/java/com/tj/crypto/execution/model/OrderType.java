package com.tj.crypto.execution.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单类型枚举。
 */
@Getter
@AllArgsConstructor
public enum OrderType {
    MARKET("market", "市价单"),
    LIMIT("limit", "限价单"),
    ;

    private final String code;
    private final String displayName;
}
