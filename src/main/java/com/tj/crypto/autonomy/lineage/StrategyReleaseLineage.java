package com.tj.crypto.autonomy.lineage;

/** Signed strategy artifact and exact runtime release assignment. */
public record StrategyReleaseLineage(
        String strategyId,
        String releaseVersion,
        String releaseAssignmentId,
        long releaseAssignmentVersion,
        String strategyArtifactSha256,
        String governanceSubjectSha256) {

    public StrategyReleaseLineage {
        strategyId = OrderDecisionLineageValues.text(strategyId, "strategyId");
        releaseVersion = OrderDecisionLineageValues.text(releaseVersion, "releaseVersion");
        releaseAssignmentId = OrderDecisionLineageValues.text(
                releaseAssignmentId, "releaseAssignmentId");
        if (releaseAssignmentVersion < 0) {
            throw new IllegalArgumentException("releaseAssignmentVersion must not be negative");
        }
        strategyArtifactSha256 = OrderDecisionLineageValues.digest(
                strategyArtifactSha256, "strategyArtifactSha256");
        governanceSubjectSha256 = OrderDecisionLineageValues.digest(
                governanceSubjectSha256, "governanceSubjectSha256");
    }
}
