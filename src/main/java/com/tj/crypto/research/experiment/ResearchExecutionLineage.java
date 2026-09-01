package com.tj.crypto.research.experiment;

/** Exact immutable registry identity bound to one executing research trial. */
public record ResearchExecutionLineage(
        String registrationId,
        String registrationSha256,
        String trialId,
        String observedProvenanceSha256) {

    public ResearchExecutionLineage {
        if (registrationId == null || registrationId.isBlank()
                || trialId == null || trialId.isBlank()) {
            throw new IllegalArgumentException("research registration and trial identity are required");
        }
        registrationSha256 = requireSha(registrationSha256, "registrationSha256");
        observedProvenanceSha256 = requireSha(
                observedProvenanceSha256, "observedProvenanceSha256");
    }

    private static String requireSha(String value, String name) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return normalized;
    }
}
