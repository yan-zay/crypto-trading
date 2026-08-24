package com.tj.crypto.trading.venue;

/** Immutable OMS lineage for every newly authorized private order. */
public record LiveOrderGovernanceBinding(String commandSha256,
                                         String authorizationKind,
                                         String governanceDecisionId,
                                         String governanceSubjectSha256,
                                         String strategyArtifactSha256) {
    static LiveOrderGovernanceBinding from(LiveOrderAuthorization authorization) {
        LiveGovernanceDecision decision = authorization.governanceDecision();
        return new LiveOrderGovernanceBinding(authorization.commandSha256(),
                authorization.kind().name(), decision == null ? null : decision.decisionId(),
                decision == null ? null : decision.governanceSubjectSha256(),
                decision == null ? null : decision.strategyArtifactSha256());
    }

    public LiveOrderGovernanceBinding {
        if (commandSha256 == null || commandSha256.length() != 64
                || authorizationKind == null || authorizationKind.isBlank()) {
            throw new IllegalArgumentException("command hash and authorization kind are required");
        }
        boolean governed = governanceDecisionId != null;
        if (governed != (governanceSubjectSha256 != null && strategyArtifactSha256 != null)) {
            throw new IllegalArgumentException("governed OMS lineage must be complete");
        }
    }
}
