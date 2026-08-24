package com.tj.crypto.autonomy.operations;

/** Revalidation of a persisted signed operator artifact and its durable anti-replay binding. */
public record PersistedControlVerification(
        boolean trusted,
        String verifierId,
        String verifiedOperatorId,
        String verifiedRole,
        String verifiedSubjectSha256,
        String verifiedPolicySha256,
        String verifiedControlSha256,
        String recomputedAttestationSha256,
        String verifiedReplayReservationId,
        long verifiedAtMs,
        String blocker
) {
    public PersistedControlVerification {
        blocker = blocker == null ? "" : blocker;
    }

    public static PersistedControlVerification untrusted(String reason) {
        return new PersistedControlVerification(false, "", "", "", "", "", "", "", "",
                0, reason == null ? "persisted control artifact is untrusted" : reason);
    }
}
