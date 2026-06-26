package com.tj.crypto.risk;

import com.tj.crypto.execution.model.OrderRejectReason;

/**
 * 风控检查结果，不可变值对象。
 *
 * @param passed        是否通过
 * @param rejectReason  拒绝原因（通过时为 NONE）
 * @param message       人类可读说明
 */
public record RiskCheckResult(
        boolean isPassed,
        OrderRejectReason rejectReason,
        String message
) {
    public static RiskCheckResult passed() {
        return new RiskCheckResult(true, OrderRejectReason.NONE, "OK");
    }

    public static RiskCheckResult rejected(OrderRejectReason reason, String message) {
        return new RiskCheckResult(false, reason, message);
    }
}
