package com.tj.crypto.autonomy.operations;

/** Untrusted approval envelope; an independent verifier must authenticate it before it counts. */
public record RecoveryApproval(
        String requestId,
        String requestSha256,
        String operatorId,
        long approvedAtMs,
        long expiresAtMs,
        String subjectSha256,
        String attestationSha256,
        String approvalSha256
) {
    private static final String AUDIENCE = "operations-recovery-approval/v1";

    public static RecoveryApproval create(RecoveryRequest request,
                                          String operatorId,
                                          long approvedAtMs,
                                          long expiresAtMs,
                                          String attestationSha256) {
        String requestId = request == null ? null : request.requestId();
        String requestSha256 = request == null ? null : request.requestSha256();
        String subjectSha256 = request == null ? null : request.subjectSha256();
        return new RecoveryApproval(requestId, requestSha256, operatorId, approvedAtMs,
                expiresAtMs, subjectSha256, attestationSha256,
                digest(requestId, requestSha256, operatorId, approvedAtMs, expiresAtMs,
                        subjectSha256, attestationSha256));
    }

    public boolean hasValidDigest() {
        return OperationsHashing.isSha256(approvalSha256)
                && approvalSha256.equals(digest(requestId, requestSha256, operatorId,
                approvedAtMs, expiresAtMs, subjectSha256, attestationSha256));
    }

    private static String digest(String requestId,
                                 String requestSha256,
                                 String operatorId,
                                 long approvedAtMs,
                                 long expiresAtMs,
                                 String subjectSha256,
                                 String attestationSha256) {
        return OperationsHashing.sha256(AUDIENCE, requestId, requestSha256, operatorId,
                approvedAtMs, expiresAtMs, subjectSha256, attestationSha256);
    }
}
