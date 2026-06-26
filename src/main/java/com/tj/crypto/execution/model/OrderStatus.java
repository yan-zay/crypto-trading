package com.tj.crypto.execution.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态枚举。
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {
    PENDING("pending", "待执行"),
    FILLED("filled", "已成交"),
    REJECTED("rejected", "已拒绝"),
    CANCELLED("cancelled", "已取消"),
    ;

    private final String code;
    private final String displayName;
}
