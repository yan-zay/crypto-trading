package com.tj.crypto.research.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

public record StructuredModelRequest(
        String task,
        JsonNode input,
        JsonNode outputSchema) {

    public StructuredModelRequest {
        if (task == null || task.isBlank() || input == null || outputSchema == null) {
            throw new IllegalArgumentException("structured model task, input and output schema are required");
        }
        task = task.trim();
    }
}
