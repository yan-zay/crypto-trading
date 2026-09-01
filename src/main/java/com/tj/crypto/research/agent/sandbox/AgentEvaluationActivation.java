package com.tj.crypto.research.agent.sandbox;

import com.tj.crypto.research.agent.policy.AgentProposalTarget;

import java.time.Instant;
import java.util.Set;

/** Durable L2 evaluation scope. It can activate only Paper/Shadow strategy evaluation. */
public record AgentEvaluationActivation(
        String proposalId,
        AgentProposalTarget target,
        String strategyName,
        String strategyArtifactSha256,
        String releaseAssignmentId,
        Set<String> instrumentScope,
        Instant expiresAt,
        String proposalUseSha256,
        boolean liveExecutionAuthorized) {

    public AgentEvaluationActivation {
        if (proposalId == null || proposalId.isBlank() || target == null
                || strategyName == null || strategyName.isBlank()
                || releaseAssignmentId == null || releaseAssignmentId.isBlank()
                || instrumentScope == null || instrumentScope.isEmpty()
                || expiresAt == null || liveExecutionAuthorized) {
            throw new IllegalArgumentException("bounded non-live Agent evaluation activation is required");
        }
        strategyArtifactSha256 = sha(strategyArtifactSha256, "strategyArtifactSha256");
        proposalUseSha256 = sha(proposalUseSha256, "proposalUseSha256");
        instrumentScope = Set.copyOf(instrumentScope);
    }

    private static String sha(String value, String name) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return normalized;
    }
}
