package com.tj.crypto.autonomy.canary;

/** Raw metrics required by every champion/challenger comparison. */
public enum CanaryMetric {
    NET_RETURN_BPS(false, false),
    TOTAL_COST_BPS(false, true),
    MAX_DRAWDOWN_BPS(false, true),
    TCA_SHORTFALL_BPS(false, false),
    UNEXPLAINED_RECONCILIATION_EVENTS(true, true),
    EXECUTED_SAMPLE_COUNT(true, true);

    private final boolean wholeNumber;
    private final boolean nonNegative;

    CanaryMetric(boolean wholeNumber, boolean nonNegative) {
        this.wholeNumber = wholeNumber;
        this.nonNegative = nonNegative;
    }

    boolean wholeNumber() {
        return wholeNumber;
    }

    boolean nonNegative() {
        return nonNegative;
    }
}
