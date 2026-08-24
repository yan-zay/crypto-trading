package com.tj.crypto.research.experiment;

public record ExperimentEventAudit(
        String eventId,
        String trialId,
        String commandIdempotencyKey,
        String commandSha256,
        long sequence,
        String eventType,
        ExperimentState fromState,
        ExperimentState toState,
        String reasonCode,
        String detailMessage,
        String observedProvenanceSha256,
        ExperimentResultArtifact resultArtifact,
        String recordedBy,
        long occurredAtEpochMillis,
        String previousEventSha256,
        String eventSha256
) {
}
