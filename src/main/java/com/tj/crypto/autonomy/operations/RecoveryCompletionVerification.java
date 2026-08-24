package com.tj.crypto.autonomy.operations;

import java.util.Set;

/** Independently authenticated human completion command with durable replay reservation. */
public record RecoveryCompletionVerification(
        boolean trusted,
        String verifierId,
        String verifiedOperatorId,
        Set<String> verifiedRoles,
        String verifiedCommandSha256,
        String verifiedRequestSha256,
        String verifiedSubjectSha256,
        String verifiedPolicySha256,
        String verifiedAudience,
        String verifiedBasisSnapshotSha256,
        String recomputedAttestationSha256,
        String replayReservationId,
        long verifiedAtMs,
        String blocker
) {
    public RecoveryCompletionVerification {
        verifiedRoles = verifiedRoles == null ? Set.of() : Set.copyOf(verifiedRoles);
        blocker = blocker == null ? "" : blocker;
    }

    public static RecoveryCompletionVerification untrusted(String reason) {
        return new RecoveryCompletionVerification(false, "", "", Set.of(), "", "", "", "", "",
                "", "", "", 0,
                reason == null ? "recovery completion is untrusted" : reason);
    }
}
