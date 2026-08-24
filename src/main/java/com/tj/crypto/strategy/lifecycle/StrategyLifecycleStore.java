package com.tj.crypto.strategy.lifecycle;

import java.util.List;
import java.util.Optional;

/** Persistence boundary; implementations must execute writes in the caller's transaction. */
public interface StrategyLifecycleStore {
    void insertArtifact(SignedStrategyArtifact artifact);

    Optional<SignedStrategyArtifact> findArtifact(String artifactSha256);

    Optional<StrategyLifecycleState> lockState(String strategyName);

    void insertState(StrategyLifecycleState state);

    boolean updateState(StrategyLifecycleState state, long expectedVersion);

    void insertApproval(StrategyApproval approval);

    List<StrategyApproval> approvals(String strategyName, String artifactSha256, long reviewCycle);

    void insertEvidence(StrategyEvidenceSnapshot evidence);

    List<StrategyEvidenceSnapshot> evidence(String strategyName, String artifactSha256);

    Optional<StrategyLifecycleEvent> latestEvent(String strategyName);

    void appendEvent(StrategyLifecycleEvent event);

    Optional<StrategyReleaseAssignment> lockReleaseAssignment(String strategyName);

    Optional<StrategyReleaseAssignment> findReleaseAssignment(String strategyName);

    void replaceReleaseAssignment(StrategyReleaseAssignment assignment, long expectedVersion);
}
