package com.tj.crypto.autonomy.agent;

import java.util.Locale;

/** 防止授权跨部署环境或治理主体重放的精确上下文。 */
public record AutonomyGovernanceContext(
        String deploymentEnvironment,
        String governanceSubjectSha256) {
    public AutonomyGovernanceContext {
        deploymentEnvironment = AutonomyValues.text(
                deploymentEnvironment, "deploymentEnvironment").toUpperCase(Locale.ROOT);
        governanceSubjectSha256 = AutonomyValues.digest(
                governanceSubjectSha256, "governanceSubjectSha256");
    }
}
