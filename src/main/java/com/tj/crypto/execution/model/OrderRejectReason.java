package com.tj.crypto.execution.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单拒绝原因枚举。
 */
@Getter
@AllArgsConstructor
public enum OrderRejectReason {
    NONE("none", "无"),
    RISK_REJECTED("risk_rejected", "风控拒绝"),
    INSUFFICIENT_BALANCE("insufficient_balance", "余额不足"),
    POSITION_EXISTS("position_exists", "持仓已存在"),
    NO_POSITION("no_position", "无持仓"),
    ;

    private final String code;
    private final String displayName;
}
