package com.tj.crypto.research.experiment;

/** Adapter boundary to a deterministic signed research-certification pipeline. */
@FunctionalInterface
public interface ExperimentCertificationEvidencePort {
    ExperimentCertificationEvidence load(String registrationId, String registrationSha256);
}
