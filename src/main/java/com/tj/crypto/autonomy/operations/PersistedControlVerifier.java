package com.tj.crypto.autonomy.operations;

/**
 * Read-only validation against signed artifact storage and a global durable anti-replay ledger.
 * Verification must not trust the receipt's self-computed hash or verifierId by itself.
 */
public interface PersistedControlVerifier {
    PersistedControlVerification verifyManualTakeover(
            ManualTakeoverLease lease, OperationsWatchdogPolicy policy, long nowMs);

    PersistedControlVerification verifyRecoveryApproval(
            RecoveryApprovalReceipt receipt,
            RecoveryRequest request,
            OperationsWatchdogPolicy policy,
            long nowMs);

    static PersistedControlVerifier failClosed() {
        return new PersistedControlVerifier() {
            @Override
            public PersistedControlVerification verifyManualTakeover(
                    ManualTakeoverLease lease, OperationsWatchdogPolicy policy, long nowMs) {
                return PersistedControlVerification.untrusted(
                        "persisted control verifier is not configured");
            }

            @Override
            public PersistedControlVerification verifyRecoveryApproval(
                    RecoveryApprovalReceipt receipt,
                    RecoveryRequest request,
                    OperationsWatchdogPolicy policy,
                    long nowMs) {
                return PersistedControlVerification.untrusted(
                        "persisted control verifier is not configured");
            }
        };
    }
}
