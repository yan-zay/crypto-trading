package com.tj.crypto.trading.paper;

import com.tj.crypto.execution.model.OrderRejectReason;

record PaperRiskDecision(boolean passed, OrderRejectReason reason, String message) {
    static PaperRiskDecision pass() {
        return new PaperRiskDecision(true, OrderRejectReason.NONE, "passed");
    }

    static PaperRiskDecision reject(OrderRejectReason reason, String message) {
        return new PaperRiskDecision(false, reason, message);
    }
}
