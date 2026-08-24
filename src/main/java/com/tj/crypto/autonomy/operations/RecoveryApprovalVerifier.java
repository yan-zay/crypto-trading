package com.tj.crypto.autonomy.operations;

/** External trust boundary for MFA/signature checks and durable anti-replay reservation. */
@FunctionalInterface
public interface RecoveryApprovalVerifier {
    RecoveryApprovalVerification verify(RecoveryApproval approval,
                                        RecoveryRequest request,
                                        long nowMs);
}
