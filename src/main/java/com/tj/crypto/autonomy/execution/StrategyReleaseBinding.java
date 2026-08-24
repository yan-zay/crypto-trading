package com.tj.crypto.autonomy.execution;

/** Exact strategy/release lineage used by one candidate compilation. */
public record StrategyReleaseBinding(
        String strategyId,
        String releaseId,
        String artifactSha256,
        String governanceSubjectSha256
) {
    public StrategyReleaseBinding {
        strategyId = ExecutionValues.id(strategyId, "strategyId", 192);
        releaseId = ExecutionValues.id(releaseId, "releaseId", 192);
        artifactSha256 = ExecutionValues.sha256(artifactSha256, "artifactSha256");
        governanceSubjectSha256 = ExecutionValues.sha256(
                governanceSubjectSha256, "governanceSubjectSha256");
    }

    public String canonicalId() {
        StringBuilder value = new StringBuilder("strategy-release-binding-v1");
        ExecutionValues.append(value, strategyId);
        ExecutionValues.append(value, releaseId);
        ExecutionValues.append(value, artifactSha256);
        ExecutionValues.append(value, governanceSubjectSha256);
        return value.toString();
    }
}
