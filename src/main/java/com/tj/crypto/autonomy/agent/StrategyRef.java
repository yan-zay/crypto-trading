package com.tj.crypto.autonomy.agent;

/** 已批准策略制品及其运行时 assignment 的资产类别无关引用。 */
public record StrategyRef(
        String strategyId,
        String releaseVersion,
        String strategyArtifactSha256,
        String runtimeAssignmentId,
        String runtimeAssignmentVersion) {
    public StrategyRef {
        strategyId = AutonomyValues.text(strategyId, "strategyId");
        releaseVersion = AutonomyValues.text(releaseVersion, "releaseVersion");
        strategyArtifactSha256 = AutonomyValues.digest(
                strategyArtifactSha256, "strategyArtifactSha256");
        runtimeAssignmentId = AutonomyValues.text(runtimeAssignmentId, "runtimeAssignmentId");
        runtimeAssignmentVersion = AutonomyValues.text(
                runtimeAssignmentVersion, "runtimeAssignmentVersion");
    }
}
