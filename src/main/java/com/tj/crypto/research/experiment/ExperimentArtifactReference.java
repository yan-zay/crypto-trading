package com.tj.crypto.research.experiment;

/** Content-addressed reference; the registry stores the reference, never mutable bytes. */
public record ExperimentArtifactReference(
        String artifactId,
        ExperimentArtifactKind kind,
        String version,
        String uri,
        String sha256,
        String mediaType
) {
    public ExperimentArtifactReference {
        artifactId = ExperimentValidation.required(artifactId, "artifactId", 100);
        if (kind == null) throw new IllegalArgumentException("artifact kind is required");
        version = ExperimentValidation.required(version, "artifact version", 160);
        uri = ExperimentValidation.required(uri, "artifact uri", 1000);
        sha256 = ExperimentValidation.sha256(sha256, "artifact sha256");
        mediaType = ExperimentValidation.required(mediaType, "artifact mediaType", 160);
    }
}
