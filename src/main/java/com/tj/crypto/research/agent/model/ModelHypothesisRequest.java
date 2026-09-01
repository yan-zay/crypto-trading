package com.tj.crypto.research.agent.model;

import java.util.List;

/** Structured, bounded input; arbitrary tool or command fields are intentionally absent. */
public record ModelHypothesisRequest(
        String researchQuestion,
        String contextManifestSha256,
        List<String> allowedVariables,
        List<String> requiredBaselines) {

    public ModelHypothesisRequest {
        if (researchQuestion == null || researchQuestion.isBlank()
                || researchQuestion.length() > 2_000) {
            throw new IllegalArgumentException("bounded researchQuestion is required");
        }
        contextManifestSha256 = contextManifestSha256 == null
                ? "" : contextManifestSha256.trim().toLowerCase();
        if (!contextManifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contextManifestSha256 must be lowercase SHA-256");
        }
        allowedVariables = bounded(allowedVariables, "allowedVariables");
        requiredBaselines = bounded(requiredBaselines, "requiredBaselines");
    }

    private static List<String> bounded(List<String> values, String name) {
        if (values == null || values.isEmpty() || values.size() > 50
                || values.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 200)) {
            throw new IllegalArgumentException(name + " must contain 1 to 50 bounded values");
        }
        return values.stream().map(String::trim).toList();
    }
}
