package com.tj.crypto.autonomy.operations;

/** Content-addressed request to transfer operational control to an authenticated human. */
public record ManualTakeoverCommand(
        String commandId,
        String operatorId,
        long issuedAtMs,
        long expiresAtMs,
        String subjectSha256,
        String basisSnapshotSha256,
        String attestationSha256,
        String commandSha256
) {
    private static final String AUDIENCE = "operations-manual-takeover/v1";

    public static ManualTakeoverCommand create(String commandId,
                                               String operatorId,
                                               long issuedAtMs,
                                               long expiresAtMs,
                                               String subjectSha256,
                                               String basisSnapshotSha256,
                                               String attestationSha256) {
        return new ManualTakeoverCommand(commandId, operatorId, issuedAtMs, expiresAtMs,
                subjectSha256, basisSnapshotSha256, attestationSha256,
                digest(commandId, operatorId, issuedAtMs, expiresAtMs, subjectSha256,
                        basisSnapshotSha256, attestationSha256));
    }

    public boolean hasValidDigest() {
        return OperationsHashing.isSha256(commandSha256)
                && commandSha256.equals(digest(commandId, operatorId, issuedAtMs, expiresAtMs,
                subjectSha256, basisSnapshotSha256, attestationSha256));
    }

    private static String digest(String commandId,
                                 String operatorId,
                                 long issuedAtMs,
                                 long expiresAtMs,
                                 String subjectSha256,
                                 String basisSnapshotSha256,
                                 String attestationSha256) {
        return OperationsHashing.sha256(AUDIENCE, commandId, operatorId, issuedAtMs, expiresAtMs,
                subjectSha256, basisSnapshotSha256, attestationSha256);
    }
}
