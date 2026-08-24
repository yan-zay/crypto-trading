package com.tj.crypto.autonomy.lineage;

/**
 * Producer provenance. Deterministic producers must explicitly declare {@link #MODEL_NONE}; Agent
 * producers must bind a proposal, model, prompt, context and complete ordered tool trace.
 */
public record DecisionProducerLineage(
        DecisionProducerKind producerKind,
        String producerId,
        String proposalId,
        String proposalSha256,
        String modelVersion,
        String promptSha256,
        String contextSha256,
        String toolTraceRootSha256) {

    public static final String MODEL_NONE = "MODEL_NONE";

    public DecisionProducerLineage {
        if (producerKind == null) throw new IllegalArgumentException("producerKind is required");
        producerId = OrderDecisionLineageValues.text(producerId, "producerId");
        modelVersion = OrderDecisionLineageValues.text(modelVersion, "modelVersion");
        switch (producerKind) {
            case DETERMINISTIC -> {
                if (!MODEL_NONE.equals(modelVersion)) {
                    throw new IllegalArgumentException("deterministic producers must use MODEL_NONE");
                }
                if (proposalId != null || proposalSha256 != null || promptSha256 != null
                        || contextSha256 != null || toolTraceRootSha256 != null) {
                    throw new IllegalArgumentException("deterministic producers cannot claim Agent lineage");
                }
            }
            case AGENT -> {
                if (MODEL_NONE.equals(modelVersion)) {
                    throw new IllegalArgumentException("Agent producers must identify the model version");
                }
                proposalId = OrderDecisionLineageValues.text(proposalId, "proposalId");
                proposalSha256 = OrderDecisionLineageValues.digest(proposalSha256, "proposalSha256");
                promptSha256 = OrderDecisionLineageValues.digest(promptSha256, "promptSha256");
                contextSha256 = OrderDecisionLineageValues.digest(contextSha256, "contextSha256");
                toolTraceRootSha256 = OrderDecisionLineageValues.digest(
                        toolTraceRootSha256, "toolTraceRootSha256");
            }
        }
    }

    public static DecisionProducerLineage deterministic(String producerId) {
        return new DecisionProducerLineage(DecisionProducerKind.DETERMINISTIC, producerId,
                null, null, MODEL_NONE, null, null, null);
    }

    public static DecisionProducerLineage agent(
            String producerId,
            String proposalId,
            String proposalSha256,
            String modelVersion,
            String promptSha256,
            String contextSha256,
            String toolTraceRootSha256) {
        return new DecisionProducerLineage(DecisionProducerKind.AGENT, producerId, proposalId,
                proposalSha256, modelVersion, promptSha256, contextSha256, toolTraceRootSha256);
    }
}
