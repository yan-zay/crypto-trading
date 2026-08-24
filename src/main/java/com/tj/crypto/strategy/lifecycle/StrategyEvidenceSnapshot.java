package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

public record StrategyEvidenceSnapshot(
        String snapshotId,
        String strategyName,
        String artifactSha256,
        StrategyEvidenceType type,
        EvidenceOutcome outcome,
        String evidenceSha256,
        String evidenceUri,
        Instant capturedAt,
        Instant expiresAt,
        String recordedBy,
        Instant recordedAt) {

    public boolean validAt(Instant now) {
        return outcome == EvidenceOutcome.PASS && !capturedAt.isAfter(now) && expiresAt.isAfter(now);
    }
}
