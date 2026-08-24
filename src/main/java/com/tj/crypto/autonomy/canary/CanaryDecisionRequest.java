package com.tj.crypto.autonomy.canary;

import java.util.Objects;

public record CanaryDecisionRequest(
        CanaryRunState runState,
        CanaryMetricSnapshot championSnapshot,
        CanaryMetricSnapshot challengerSnapshot,
        CapitalStepApproval capitalApproval
) {
    public CanaryDecisionRequest {
        Objects.requireNonNull(runState, "runState");
        Objects.requireNonNull(championSnapshot, "championSnapshot");
        Objects.requireNonNull(challengerSnapshot, "challengerSnapshot");
    }

    public String metricPairSha256() {
        return CanaryCanonical.sha256("schema=canary-metric-pair/v1\nchampionScope="
                + championSnapshot.deploymentScope().contentSha256() + "\nchallengerScope="
                + challengerSnapshot.deploymentScope().contentSha256() + "\nchampion="
                + championSnapshot.contentSha256() + "\nchallenger=" + challengerSnapshot.contentSha256());
    }
}
