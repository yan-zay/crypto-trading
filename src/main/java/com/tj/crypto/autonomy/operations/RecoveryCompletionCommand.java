package com.tj.crypto.autonomy.operations;

/** Fresh human command that explicitly consumes a dual-approved recovery request. */
public record RecoveryCompletionCommand(
        String requestId,
        String requestSha256,
        String operatorId,
        long issuedAtMs,
        long expiresAtMs,
        String subjectSha256,
        String basisSnapshotSha256,
        String attestationSha256,
        String commandSha256
) {
    private static final String AUDIENCE = "operations-recovery-completion/v1";

    public static RecoveryCompletionCommand create(RecoveryRequest request,
                                                   String operatorId,
                                                   long issuedAtMs,
                                                   long expiresAtMs,
                                                   String basisSnapshotSha256,
                                                   String attestationSha256) {
        String requestId = request == null ? null : request.requestId();
        String requestSha256 = request == null ? null : request.requestSha256();
        String subjectSha256 = request == null ? null : request.subjectSha256();
        String digest = digest(requestId, requestSha256, operatorId, issuedAtMs, expiresAtMs,
                subjectSha256, basisSnapshotSha256, attestationSha256);
        return new RecoveryCompletionCommand(requestId, requestSha256, operatorId, issuedAtMs,
                expiresAtMs, subjectSha256, basisSnapshotSha256, attestationSha256, digest);
    }

    public boolean hasValidDigest() {
        return OperationsHashing.isSha256(commandSha256)
                && commandSha256.equals(digest(requestId, requestSha256, operatorId, issuedAtMs,
                expiresAtMs, subjectSha256, basisSnapshotSha256, attestationSha256));
    }

    private static String digest(String requestId,
                                 String requestSha256,
                                 String operatorId,
                                 long issuedAtMs,
                                 long expiresAtMs,
                                 String subjectSha256,
                                 String basisSnapshotSha256,
                                 String attestationSha256) {
        return OperationsHashing.sha256(AUDIENCE, requestId, requestSha256, operatorId, issuedAtMs,
                expiresAtMs, subjectSha256, basisSnapshotSha256, attestationSha256);
    }
}
