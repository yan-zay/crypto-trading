package com.tj.crypto.execution.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态枚举。
 *
 * 状态机流转：
 * <pre>
 *   CREATED → SUBMITTED → ACKNOWLEDGED → PARTIALLY_FILLED → FILLED
 *                                           ↓
 *                                     CANCEL_REQUESTED → CANCELLED
 *
 *   任意阶段可 → REJECTED / EXPIRED
 * </pre>
 *
 * PENDING 为历史兼容别名，等价于 CREATED。
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {
    /** @deprecated 使用 {@link #CREATED} 代替 */
    @Deprecated
    PENDING("pending", "待执行", true),
    CREATED("created", "已创建", true),
    SUBMITTED("submitted", "已提交", true),
    ACKNOWLEDGED("acknowledged", "已确认", true),
    PARTIALLY_FILLED("partially_filled", "部分成交", true),
    FILLED("filled", "已成交", false),
    CANCEL_REQUESTED("cancel_requested", "撤单请求中", true),
    /** Venue outcome is temporarily indeterminate and must be reconciled before retrying. */
    UNKNOWN("unknown", "状态待对账", true),
    CANCELLED("cancelled", "已取消", false),
    REJECTED("rejected", "已拒绝", false),
    EXPIRED("expired", "已过期", false),
    ;

    private final String code;
    private final String displayName;

    /**
     * 是否为活跃状态（仍可接收新事件）。
     */
    private final boolean active;
}
