package com.tj.crypto.autonomy.operations;

/** Persistable, expiring proof of which verified human owns manual operational control. */
public record ManualTakeoverLease(
        String commandId,
        String operatorId,
        String verifierId,
        long issuedAtMs,
        long expiresAtMs,
        String subjectSha256,
        String basisSnapshotSha256,
        String attestationSha256,
        String replayReservationId,
        String leaseSha256
) {
    private static final String AUDIENCE = "operations-manual-takeover-lease/v1";

    static ManualTakeoverLease create(ManualTakeoverCommand command,
                                      ManualTakeoverVerification verification) {
        String digest = OperationsHashing.sha256(AUDIENCE, command.commandId(),
                verification.verifiedOperatorId(), verification.verifierId(), command.issuedAtMs(),
                command.expiresAtMs(), command.subjectSha256(), command.basisSnapshotSha256(),
                verification.recomputedAttestationSha256(), verification.replayReservationId());
        return new ManualTakeoverLease(command.commandId(), verification.verifiedOperatorId(),
                verification.verifierId(), command.issuedAtMs(), command.expiresAtMs(),
                command.subjectSha256(), command.basisSnapshotSha256(),
                verification.recomputedAttestationSha256(), verification.replayReservationId(),
                digest);
    }

    boolean hasValidDigest() {
        return OperationsHashing.isSha256(leaseSha256)
                && leaseSha256.equals(OperationsHashing.sha256(AUDIENCE, commandId, operatorId,
                verifierId, issuedAtMs, expiresAtMs, subjectSha256, basisSnapshotSha256,
                attestationSha256, replayReservationId));
    }
}
