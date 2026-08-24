package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;
import java.util.List;

/** Snapshot supplied by an independent legal/security/operations/live-truth evidence gate. */
public record ExternalPromotionReadiness(
        boolean ready,
        String evidenceSetSha256,
        Instant observedAt,
        Instant expiresAt,
        List<String> blockers) {

    public ExternalPromotionReadiness {
        if (evidenceSetSha256 != null) Hashes.requireSha256(evidenceSetSha256, "evidenceSetSha256");
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        if (observedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("external readiness timestamps are required");
        }
        if (ready && (!blockers.isEmpty() || !expiresAt.isAfter(observedAt)
                || evidenceSetSha256 == null)) {
            throw new IllegalArgumentException("ready external evidence must be hashed, current and blocker-free");
        }
    }

    public boolean currentAt(Instant now) {
        return ready && !observedAt.isAfter(now) && expiresAt.isAfter(now);
    }
}
