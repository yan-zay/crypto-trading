package com.tj.crypto.autonomy.readiness;

import java.util.List;

/** Explicit NOT_VERIFIED result from an unavailable or rejecting trust adapter. */
public record RejectedEvidenceVerification(List<String> blockers)
        implements EvidenceVerification {

    public RejectedEvidenceVerification {
        blockers = List.copyOf(blockers == null || blockers.isEmpty()
                ? List.of("external evidence is NOT_VERIFIED") : blockers);
    }

    public static RejectedEvidenceVerification notVerified(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        return new RejectedEvidenceVerification(List.of(normalized.isEmpty()
                ? "external evidence is NOT_VERIFIED" : normalized));
    }
}
