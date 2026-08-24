package com.tj.crypto.research.experiment;

import java.util.Map;
import java.util.TreeMap;

/** Inputs observed by a worker immediately before it starts a registered trial. */
public record ExperimentReproductionInputs(
        String registrationSha256,
        Map<String, String> artifactSha256ById,
        long randomSeed,
        String parameterSha256,
        String modelVersion,
        String promptVersion
) {
    public ExperimentReproductionInputs {
        registrationSha256 = ExperimentValidation.sha256(registrationSha256, "registration sha256");
        parameterSha256 = ExperimentValidation.sha256(parameterSha256, "parameter sha256");
        modelVersion = ExperimentValidation.required(modelVersion, "modelVersion", 160);
        promptVersion = ExperimentValidation.required(promptVersion, "promptVersion", 160);
        if (artifactSha256ById == null) {
            throw new IllegalArgumentException("artifactSha256ById is required");
        }
        Map<String, String> normalized = new TreeMap<>();
        artifactSha256ById.forEach((id, digest) -> {
            String normalizedId = ExperimentValidation.required(id, "artifact id", 100);
            String prior = normalized.put(normalizedId,
                    ExperimentValidation.sha256(digest, "artifact sha256"));
            if (prior != null) {
                throw new IllegalArgumentException("duplicate normalized artifact id: " + normalizedId);
            }
        });
        artifactSha256ById = Map.copyOf(normalized);
    }
}
