package com.tj.crypto.autonomy.operations;

/**
 * Untrusted fact envelope. Validation intentionally happens inside the state machine so malformed,
 * unknown and conflicting external inputs produce a fail-closed decision instead of escaping as an
 * exception before a safety state can be emitted.
 */
public record OperationsObservation(
        String inputType,
        String source,
        long sequence,
        long observedAtMs,
        long expiresAtMs,
        OperationsHealth health,
        String subjectSha256,
        String payloadSha256,
        String attestationSha256,
        String observationSha256
) {
    private static final String AUDIENCE = "operations-watchdog-observation/v1";

    public static OperationsObservation create(OperationsObservationKind kind,
                                               String source,
                                               long sequence,
                                               long observedAtMs,
                                               long expiresAtMs,
                                               OperationsHealth health,
                                               String subjectSha256,
                                               String payloadSha256,
                                               String attestationSha256) {
        String digest = digest(kind == null ? null : kind.name(), source, sequence, observedAtMs,
                expiresAtMs, health, subjectSha256, payloadSha256, attestationSha256);
        return new OperationsObservation(kind == null ? null : kind.name(), source, sequence,
                observedAtMs, expiresAtMs, health, subjectSha256, payloadSha256,
                attestationSha256, digest);
    }

    public boolean hasValidDigest() {
        return OperationsHashing.isSha256(observationSha256)
                && observationSha256.equals(digest(inputType, source, sequence, observedAtMs,
                expiresAtMs, health, subjectSha256, payloadSha256, attestationSha256));
    }

    private static String digest(String inputType,
                                 String source,
                                 long sequence,
                                 long observedAtMs,
                                 long expiresAtMs,
                                 OperationsHealth health,
                                 String subjectSha256,
                                 String payloadSha256,
                                 String attestationSha256) {
        return OperationsHashing.sha256(AUDIENCE, inputType, source, sequence, observedAtMs,
                expiresAtMs, health == null ? null : health.name(), subjectSha256, payloadSha256,
                attestationSha256);
    }
}
