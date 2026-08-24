package com.tj.crypto.autonomy.canary;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/** Content-addressed decision only; applying it atomically belongs to a separate trusted owner. */
public record CanaryDecision(
        String decisionSha256,
        CanaryAction action,
        List<CanaryReasonCode> reasonCodes,
        String policySha256,
        String deploymentScopeSha256,
        String runStateSha256,
        String metricPairSha256,
        long evaluatedStateVersion,
        String fromRungId,
        String targetRungId,
        String rollbackReleaseArtifactSha256,
        Instant decidedAt
) {
    public CanaryDecision {
        decisionSha256 = CanaryCanonical.required(decisionSha256, "decisionSha256");
        action = Objects.requireNonNull(action, "action");
        reasonCodes = List.copyOf(reasonCodes);
        if (reasonCodes.isEmpty()) throw new IllegalArgumentException("at least one reason code is required");
        policySha256 = CanaryCanonical.required(policySha256, "policySha256");
        deploymentScopeSha256 = CanaryCanonical.required(deploymentScopeSha256, "deploymentScopeSha256");
        runStateSha256 = CanaryCanonical.required(runStateSha256, "runStateSha256");
        metricPairSha256 = CanaryCanonical.required(metricPairSha256, "metricPairSha256");
        if (evaluatedStateVersion < 0) throw new IllegalArgumentException("state version cannot be negative");
        fromRungId = fromRungId == null ? "" : fromRungId.trim();
        targetRungId = targetRungId == null ? "" : targetRungId.trim();
        rollbackReleaseArtifactSha256 = rollbackReleaseArtifactSha256 == null
                ? "" : rollbackReleaseArtifactSha256.trim();
        Objects.requireNonNull(decidedAt, "decidedAt");
        String expected = digest(action, reasonCodes, policySha256, deploymentScopeSha256,
                runStateSha256, metricPairSha256,
                evaluatedStateVersion, fromRungId, targetRungId, rollbackReleaseArtifactSha256, decidedAt);
        if (!decisionSha256.equals(expected)) throw new IllegalArgumentException("decision content digest mismatch");
    }

    static CanaryDecision issue(CanaryAction action,
                                List<CanaryReasonCode> reasonCodes,
                                CanaryPolicy policy,
                                CanaryDecisionRequest request,
                                String targetRungId,
                                String rollbackReleaseArtifactSha256,
                                Instant decidedAt) {
        String fromRung = request.runState().stage() == CanaryStage.SHADOW
                ? "SHADOW" : request.runState().currentRungId();
        String target = targetRungId == null ? "" : targetRungId;
        String rollback = rollbackReleaseArtifactSha256 == null ? "" : rollbackReleaseArtifactSha256;
        String scopeSha256 = request.runState().deploymentScope().contentSha256();
        String runStateSha256 = request.runState().contentSha256();
        String digest = digest(action, reasonCodes, policy.contentSha256(), scopeSha256, runStateSha256,
                request.metricPairSha256(),
                request.runState().stateVersion(), fromRung, target, rollback, decidedAt);
        return new CanaryDecision(digest, action, reasonCodes, policy.contentSha256(),
                scopeSha256, runStateSha256, request.metricPairSha256(),
                request.runState().stateVersion(), fromRung,
                target, rollback, decidedAt);
    }

    public CanaryRiskDirection riskDirection() {
        return switch (action) {
            case ENTER_TINY_CANARY, ADVANCE_ONE_RUNG -> CanaryRiskDirection.INCREASE;
            case HOLD -> CanaryRiskDirection.UNCHANGED;
            case DOWNGRADE_ONE_RUNG, ROLLBACK_TO_CHAMPION -> CanaryRiskDirection.DECREASE;
            case HALT -> CanaryRiskDirection.FREEZE;
        };
    }

    private static String digest(CanaryAction action,
                                 List<CanaryReasonCode> reasons,
                                 String policySha256,
                                 String deploymentScopeSha256,
                                 String runStateSha256,
                                 String metricPairSha256,
                                 long stateVersion,
                                 String fromRungId,
                                 String targetRungId,
                                 String rollbackReleaseArtifactSha256,
                                 Instant decidedAt) {
        StringJoiner canonical = new StringJoiner("\n");
        canonical.add("schema=canary-decision/v1")
                .add("action=" + action.name())
                .add("policySha256=" + policySha256)
                .add("deploymentScopeSha256=" + deploymentScopeSha256)
                .add("runStateSha256=" + runStateSha256)
                .add("metricPairSha256=" + metricPairSha256)
                .add("evaluatedStateVersion=" + stateVersion)
                .add("fromRungId=" + fromRungId)
                .add("targetRungId=" + targetRungId)
                .add("rollbackReleaseArtifactSha256=" + rollbackReleaseArtifactSha256)
                .add("decidedAt=" + decidedAt.toEpochMilli());
        for (CanaryReasonCode reason : reasons) canonical.add("reason=" + reason.name());
        return CanaryCanonical.sha256(canonical.toString());
    }
}
