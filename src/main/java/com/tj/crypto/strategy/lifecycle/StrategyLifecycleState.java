package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

/** Mutable cursor; all evidence, approvals and transitions remain append-only. */
public record StrategyLifecycleState(
        String strategyName,
        String artifactSha256,
        String previousArtifactSha256,
        StrategyLifecycleStage stage,
        long reviewCycle,
        long stateVersion,
        String updatedBy,
        Instant updatedAt,
        LifecycleReason policyReason) {

    public StrategyLifecycleState {
        requireText(strategyName, "strategyName");
        Hashes.requireSha256(artifactSha256, "artifactSha256");
        if (previousArtifactSha256 != null) Hashes.requireSha256(previousArtifactSha256, "previousArtifactSha256");
        if (stage == null || updatedAt == null || policyReason == null) {
            throw new IllegalArgumentException("stage, updatedAt and policyReason are required");
        }
        requireText(updatedBy, "updatedBy");
        if (reviewCycle < 0 || stateVersion < 0) throw new IllegalArgumentException("versions cannot be negative");
    }

    public StrategyLifecycleState move(StrategyLifecycleStage next, String artifact,
                                       String previousArtifact, long nextReviewCycle,
                                       String operator, Instant at, LifecycleReason reason) {
        return new StrategyLifecycleState(strategyName, artifact, previousArtifact, next,
                nextReviewCycle, stateVersion + 1, operator, at, reason);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
