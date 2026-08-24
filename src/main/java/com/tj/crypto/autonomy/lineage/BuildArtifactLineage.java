package com.tj.crypto.autonomy.lineage;

/** Reproducible source, build, container and configuration identity. */
public record BuildArtifactLineage(
        String gitCommitHash,
        String buildArtifactSha256,
        String containerImageSha256,
        String configurationSha256) {

    public BuildArtifactLineage {
        gitCommitHash = OrderDecisionLineageValues.gitObject(gitCommitHash);
        buildArtifactSha256 = OrderDecisionLineageValues.digest(
                buildArtifactSha256, "buildArtifactSha256");
        containerImageSha256 = OrderDecisionLineageValues.digest(
                containerImageSha256, "containerImageSha256");
        configurationSha256 = OrderDecisionLineageValues.digest(
                configurationSha256, "configurationSha256");
    }
}
