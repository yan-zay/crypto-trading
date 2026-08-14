package com.tj.crypto.trading.reconciliation;

/** Small immutable result used by tests and operational callers. */
public record LiveOrderRecoveryRun(
        int discovered,
        int reconciled,
        int stillActive,
        int terminal,
        int failed,
        boolean skipped,
        boolean backlogRemaining,
        boolean recoveryComplete
) {
    /** Source-compatible constructor for callers that do not yet consume readiness details. */
    public LiveOrderRecoveryRun(int discovered, int reconciled, int stillActive, int terminal,
                                int failed, boolean skipped) {
        this(discovered, reconciled, stillActive, terminal, failed, skipped, false,
                !skipped && failed == 0);
    }

    public static LiveOrderRecoveryRun skippedRun() {
        return new LiveOrderRecoveryRun(0, 0, 0, 0, 0, true, false, false);
    }
}
