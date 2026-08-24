package com.tj.crypto.strategy.lifecycle;

/** Exact release identity attached to every signal emitted by the managed runtime. */
public record StrategyRuntimeRelease(
        String assignmentId,
        String artifactSha256,
        String governanceSubjectSha256,
        StrategyLifecycleStage stage,
        boolean lifecycleBound) {

    public StrategyRuntimeRelease {
        if (assignmentId == null || assignmentId.isBlank() || stage == null) {
            throw new IllegalArgumentException("assignmentId and stage are required");
        }
        if (lifecycleBound) {
            Hashes.requireSha256(artifactSha256, "artifactSha256");
            Hashes.requireSha256(governanceSubjectSha256, "governanceSubjectSha256");
        }
    }

    static StrategyRuntimeRelease configurationOnlyCompatibility(String strategyName) {
        return new StrategyRuntimeRelease("CONFIGURATION_ONLY_TEST:" + strategyName,
                null, null, StrategyLifecycleStage.SHADOW, false);
    }
}
