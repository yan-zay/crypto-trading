package com.tj.crypto.research.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

public record StructuredModelResult(
        JsonNode output,
        String modelVersion,
        String promptVersion,
        String requestSha256,
        String responseSha256,
        String outputSha256,
        String evalReportSha256,
        long latencyMs,
        long costMicrousd) {

    public StructuredModelResult {
        if (output == null || modelVersion == null || modelVersion.isBlank()
                || promptVersion == null || promptVersion.isBlank()
                || latencyMs < 0 || costMicrousd < 0) {
            throw new IllegalArgumentException("complete structured model result is required");
        }
        requestSha256 = sha(requestSha256, "requestSha256");
        responseSha256 = sha(responseSha256, "responseSha256");
        outputSha256 = sha(outputSha256, "outputSha256");
        evalReportSha256 = sha(evalReportSha256, "evalReportSha256");
    }

    private static String sha(String value, String name) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return normalized;
    }
}
