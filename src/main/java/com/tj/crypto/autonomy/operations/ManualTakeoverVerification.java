package com.tj.crypto.autonomy.operations;

import java.util.Set;

/** Result produced by an independent IdP/operator authorization adapter. */
public record ManualTakeoverVerification(
        boolean trusted,
        String verifierId,
        String verifiedOperatorId,
        Set<String> verifiedRoles,
        String verifiedCommandSha256,
        String verifiedSubjectSha256,
        String verifiedPolicySha256,
        String verifiedAudience,
        String verifiedBasisSnapshotSha256,
        String recomputedAttestationSha256,
        String replayReservationId,
        long verifiedAtMs,
        String blocker
) {
    public ManualTakeoverVerification {
        verifiedRoles = verifiedRoles == null ? Set.of() : Set.copyOf(verifiedRoles);
        blocker = blocker == null ? "" : blocker;
    }

    public static ManualTakeoverVerification untrusted(String reason) {
        return new ManualTakeoverVerification(false, "", "", Set.of(), "", "", "", "", "",
                "", "", 0, reason == null ? "operator verification failed" : reason);
    }
}
