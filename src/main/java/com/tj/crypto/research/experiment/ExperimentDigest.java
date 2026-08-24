package com.tj.crypto.research.experiment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;

final class ExperimentDigest {
    static final String GENESIS_SHA256 = "0".repeat(64);

    private ExperimentDigest() {
    }

    static String registration(ExperimentFamilyRegistration registration) {
        DigestWriter digest = new DigestWriter();
        digest.field("schema", "research-experiment-registration-v1")
                .field("experimentName", registration.experimentName())
                .field("hypothesis", registration.hypothesis())
                .field("protocolVersion", registration.protocolVersion())
                .field("modelVersion", registration.modelVersion())
                .field("promptVersion", registration.promptVersion())
                .field("declaredTrialCount", registration.declaredTrialCount());
        for (ExperimentArtifactReference artifact : registration.artifacts()) {
            digest.field("artifactId", artifact.artifactId())
                    .field("artifactKind", artifact.kind())
                    .field("artifactVersion", artifact.version())
                    .field("artifactUri", artifact.uri())
                    .field("artifactSha256", artifact.sha256())
                    .field("artifactMediaType", artifact.mediaType());
        }
        for (ExperimentTrialDefinition trial : registration.trials()) {
            digest.field("trialId", trial.trialId())
                    .field("trialOrdinal", trial.ordinal())
                    .field("trialHypothesis", trial.hypothesis())
                    .field("randomSeed", trial.randomSeed())
                    .field("parameterSha256", trial.parameterSha256());
        }
        return digest.finish();
    }

    static String provenance(ExperimentReproductionInputs inputs) {
        DigestWriter digest = new DigestWriter()
                .field("schema", "research-experiment-provenance-v1")
                .field("registrationSha256", inputs.registrationSha256())
                .field("randomSeed", inputs.randomSeed())
                .field("parameterSha256", inputs.parameterSha256())
                .field("modelVersion", inputs.modelVersion())
                .field("promptVersion", inputs.promptVersion());
        new TreeMap<>(inputs.artifactSha256ById()).forEach((id, sha) -> digest
                .field("artifactId", id).field("artifactSha256", sha));
        return digest.finish();
    }

    static String event(ExperimentEventDO event) {
        return new DigestWriter()
                .field("schema", "research-experiment-event-v1")
                .field("eventId", event.getEventId())
                .field("registrationId", event.getRegistrationId())
                .field("trialId", event.getTrialId())
                .field("commandIdempotencyKey", event.getCommandIdempotencyKey())
                .field("commandSha256", event.getCommandSha256())
                .field("eventSequence", event.getEventSequence())
                .field("eventType", event.getEventType())
                .field("fromStatus", event.getFromStatus())
                .field("toStatus", event.getToStatus())
                .field("reasonCode", event.getReasonCode())
                .field("detailMessage", event.getDetailMessage())
                .field("observedProvenanceSha256", event.getObservedProvenanceSha256())
                .field("resultArtifactUri", event.getResultArtifactUri())
                .field("resultArtifactVersion", event.getResultArtifactVersion())
                .field("resultArtifactSha256", event.getResultArtifactSha256())
                .field("recordedBy", event.getRecordedBy())
                .field("occurredAtMs", event.getOccurredAtMs())
                .field("previousEventSha256", event.getPreviousEventSha256())
                .finish();
    }

    static String command(String registrationId, String trialId, String commandIdempotencyKey,
                          String eventType, ExperimentState target, String reasonCode,
                          String detailMessage, String observedProvenanceSha256,
                          ExperimentResultArtifact resultArtifact, String recordedBy) {
        return new DigestWriter()
                .field("schema", "research-experiment-command-v1")
                .field("registrationId", registrationId)
                .field("trialId", trialId)
                .field("commandIdempotencyKey", commandIdempotencyKey)
                .field("eventType", eventType)
                .field("target", target)
                .field("reasonCode", reasonCode)
                .field("detailMessage", detailMessage)
                .field("observedProvenanceSha256", observedProvenanceSha256)
                .field("resultArtifactUri", resultArtifact == null ? null : resultArtifact.uri())
                .field("resultArtifactVersion", resultArtifact == null ? null : resultArtifact.version())
                .field("resultArtifactSha256", resultArtifact == null ? null : resultArtifact.sha256())
                .field("recordedBy", recordedBy)
                .finish();
    }

    private static final class DigestWriter {
        private final MessageDigest digest;

        private DigestWriter() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("JVM does not support SHA-256", e);
            }
        }

        private DigestWriter field(String name, Object value) {
            bytes(name.getBytes(StandardCharsets.UTF_8));
            bytes(value == null ? new byte[0]
                    : String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return this;
        }

        private void bytes(byte[] value) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            digest.update(value);
        }

        private String finish() {
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }
}
