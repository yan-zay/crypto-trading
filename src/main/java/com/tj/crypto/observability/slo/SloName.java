package com.tj.crypto.observability.slo;

import java.math.BigDecimal;

/** Auditable service-level objectives for the critical trading workflow. */
public enum SloName {
    PAPER_ORDER_AVAILABILITY("0.999"),
    PAPER_ORDER_LATENCY("0.990"),
    OUTBOX_DELIVERY("0.999"),
    RECONCILIATION_CONSISTENCY("1.000"),
    BACKTEST_JOB_COMPLETION("0.990"),
    MARKET_DATA_FRESHNESS("0.990"),
    AUDIT_APPEND("0.999");

    private final BigDecimal target;

    SloName(String target) {
        this.target = new BigDecimal(target);
    }

    public BigDecimal target() {
        return target;
    }
}
