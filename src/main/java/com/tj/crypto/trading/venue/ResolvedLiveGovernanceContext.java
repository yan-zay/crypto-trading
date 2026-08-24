package com.tj.crypto.trading.venue;

import com.tj.crypto.governance.evidence.GovernanceSubject;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStage;

import java.time.Instant;

record ResolvedLiveGovernanceContext(String assignmentId,
                                     long assignmentVersion,
                                     String strategyName,
                                     String artifactSha256,
                                     StrategyLifecycleStage currentStage,
                                     GovernanceSubject subject,
                                     Instant expiresAt) {
    ResolvedLiveGovernanceContext {
        if (assignmentId == null || assignmentId.isBlank() || assignmentVersion < 0
                || strategyName == null || strategyName.isBlank() || artifactSha256 == null
                || currentStage == null || subject == null || expiresAt == null) {
            throw new IllegalArgumentException("complete live governance context is required");
        }
        if (!artifactSha256.equals(subject.strategyArtifactSha256())) {
            throw new IllegalArgumentException("release artifact differs from governance subject");
        }
    }
}
