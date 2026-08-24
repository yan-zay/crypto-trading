package com.tj.crypto.autonomy.operations;

import java.util.Set;

/** Independently recomputed recovery approval identity, scope and replay reservation. */
public record RecoveryApprovalVerification(
        boolean trusted,
        String verifierId,
        String verifiedOperatorId,
        Set<String> verifiedRoles,
        String verifiedRequestSha256,
        String verifiedApprovalSha256,
        String verifiedSubjectSha256,
        String verifiedPolicySha256,
        String verifiedAudience,
        String recomputedAttestationSha256,
        String replayReservationId,
        long verifiedAtMs,
        String blocker
) {
    public RecoveryApprovalVerification {
        verifiedRoles = verifiedRoles == null ? Set.of() : Set.copyOf(verifiedRoles);
        blocker = blocker == null ? "" : blocker;
    }

    public static RecoveryApprovalVerification untrusted(String reason) {
        return new RecoveryApprovalVerification(false, "", "", Set.of(), "", "", "", "", "",
                "", "", 0, reason == null ? "recovery approval is untrusted" : reason);
    }
}
