package com.tj.crypto.strategy.lifecycle;

import com.tj.crypto.governance.evidence.GovernanceSubject;

import java.time.Instant;

/** Immutable version of the exact signed release and governance context assigned to runtime. */
public record StrategyReleaseAssignment(
        String assignmentId,
        String strategyName,
        String artifactSha256,
        StrategyLifecycleStage minimumStage,
        GovernanceSubject governanceSubject,
        Instant expiresAt,
        long assignmentVersion,
        String assignedBy,
        Instant assignedAt) {

    public StrategyReleaseAssignment {
        requireText(assignmentId, "assignmentId");
        requireText(strategyName, "strategyName");
        Hashes.requireSha256(artifactSha256, "artifactSha256");
        if (minimumStage != StrategyLifecycleStage.SHADOW
                && minimumStage != StrategyLifecycleStage.CANARY
                && minimumStage != StrategyLifecycleStage.PROMOTED) {
            throw new IllegalArgumentException("runtime assignment stage must be SHADOW, CANARY or PROMOTED");
        }
        if (governanceSubject == null || expiresAt == null || assignedAt == null) {
            throw new IllegalArgumentException("governanceSubject and assignment timestamps are required");
        }
        if (!artifactSha256.equals(governanceSubject.strategyArtifactSha256())) {
            throw new IllegalArgumentException("governance subject is bound to a different strategy artifact");
        }
        if (!expiresAt.isAfter(assignedAt)) {
            throw new IllegalArgumentException("release assignment must have a future expiry");
        }
        if (assignmentVersion < 0) throw new IllegalArgumentException("assignmentVersion cannot be negative");
        requireText(assignedBy, "assignedBy");
    }

    public boolean currentAt(Instant now) {
        return !assignedAt.isAfter(now) && expiresAt.isAfter(now);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
