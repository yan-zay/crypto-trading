package com.tj.crypto.autonomy.operations;

/** Signature/attestation result produced outside the watchdog by a deterministic trust adapter. */
public record OperationsObservationVerification(
        boolean trusted,
        String verifierId,
        String verifiedSource,
        String verifiedObservationSha256,
        String verifiedSubjectSha256,
        String recomputedAttestationSha256,
        long verifiedAtMs,
        String blocker
) {
    public OperationsObservationVerification {
        blocker = blocker == null ? "" : blocker;
    }

    public static OperationsObservationVerification untrusted(String reason) {
        return new OperationsObservationVerification(false, "", "", "", "", "", 0,
                reason == null ? "observation attestation is untrusted" : reason);
    }
}
