package com.tj.crypto.autonomy.operations;

/** A recovery request bound to one exact watchdog snapshot and one stepwise target. */
public record RecoveryRequest(
        String requestId,
        String requestedBy,
        RecoveryTarget target,
        long requestedAtMs,
        long expiresAtMs,
        String subjectSha256,
        String basisSnapshotSha256,
        String purposeSha256,
        String requestSha256
) {
    private static final String AUDIENCE = "operations-recovery-request/v1";

    public static RecoveryRequest create(String requestId,
                                         String requestedBy,
                                         RecoveryTarget target,
                                         long requestedAtMs,
                                         long expiresAtMs,
                                         String subjectSha256,
                                         String basisSnapshotSha256,
                                         String purposeSha256) {
        return new RecoveryRequest(requestId, requestedBy, target, requestedAtMs, expiresAtMs,
                subjectSha256, basisSnapshotSha256, purposeSha256,
                digest(requestId, requestedBy, target, requestedAtMs, expiresAtMs,
                        subjectSha256, basisSnapshotSha256, purposeSha256));
    }

    public boolean hasValidDigest() {
        return OperationsHashing.isSha256(requestSha256)
                && requestSha256.equals(digest(requestId, requestedBy, target, requestedAtMs,
                expiresAtMs, subjectSha256, basisSnapshotSha256, purposeSha256));
    }

    private static String digest(String requestId,
                                 String requestedBy,
                                 RecoveryTarget target,
                                 long requestedAtMs,
                                 long expiresAtMs,
                                 String subjectSha256,
                                 String basisSnapshotSha256,
                                 String purposeSha256) {
        return OperationsHashing.sha256(AUDIENCE, requestId, requestedBy,
                target == null ? null : target.name(), requestedAtMs, expiresAtMs,
                subjectSha256, basisSnapshotSha256, purposeSha256);
    }
}
