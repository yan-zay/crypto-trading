package com.tj.crypto.research.experiment;

/** Content-addressed output or failure-log artifact appended to a lifecycle event. */
public record ExperimentResultArtifact(String uri, String version, String sha256) {
    public ExperimentResultArtifact {
        uri = ExperimentValidation.required(uri, "result artifact uri", 1000);
        version = ExperimentValidation.required(version, "result artifact version", 160);
        sha256 = ExperimentValidation.sha256(sha256, "result artifact sha256");
    }
}
