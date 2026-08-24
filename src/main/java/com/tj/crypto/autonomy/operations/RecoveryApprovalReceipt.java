package com.tj.crypto.autonomy.operations;

/** Immutable verifier receipt retained in the state snapshot for dual-control evaluation. */
public record RecoveryApprovalReceipt(
        String operatorId,
        String requestSha256,
        String approvalSha256,
        String verifierId,
        String attestationSha256,
        String replayReservationId,
        long verifiedAtMs,
        long expiresAtMs,
        String receiptSha256
) {
    private static final String AUDIENCE = "operations-recovery-receipt/v1";

    static RecoveryApprovalReceipt create(RecoveryApproval approval,
                                          RecoveryApprovalVerification verification) {
        String digest = OperationsHashing.sha256(AUDIENCE, verification.verifiedOperatorId(),
                approval.requestSha256(), approval.approvalSha256(), verification.verifierId(),
                verification.recomputedAttestationSha256(), verification.replayReservationId(),
                verification.verifiedAtMs(), approval.expiresAtMs());
        return new RecoveryApprovalReceipt(verification.verifiedOperatorId(),
                approval.requestSha256(), approval.approvalSha256(), verification.verifierId(),
                verification.recomputedAttestationSha256(), verification.replayReservationId(),
                verification.verifiedAtMs(), approval.expiresAtMs(), digest);
    }

    boolean hasValidDigest() {
        return OperationsHashing.isSha256(receiptSha256)
                && receiptSha256.equals(OperationsHashing.sha256(AUDIENCE, operatorId,
                requestSha256, approvalSha256, verifierId, attestationSha256, replayReservationId,
                verifiedAtMs, expiresAtMs));
    }
}
