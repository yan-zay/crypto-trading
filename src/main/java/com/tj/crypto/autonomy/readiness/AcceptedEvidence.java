package com.tj.crypto.autonomy.readiness;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** Audit summary of one externally verified claim accepted into a decision. */
public record AcceptedEvidence(
        String evidenceId,
        String contentSha256,
        URI artifactUri,
        String schemaVersion,
        String verifierReceiptId,
        String verifierId,
        String verifiedIssuer,
        String signatureKeyId,
        SignatureAlgorithm signatureAlgorithm,
        Instant verifiedAt,
        Instant observedAt,
        Instant expiresAt) {

    public AcceptedEvidence {
        evidenceId = ReadinessValues.required(evidenceId, "evidenceId", 128);
        contentSha256 = ReadinessValues.hash(contentSha256, "contentSha256");
        artifactUri = Objects.requireNonNull(artifactUri, "artifactUri");
        schemaVersion = ReadinessValues.required(schemaVersion, "schemaVersion", 160);
        verifierReceiptId = ReadinessValues.required(
                verifierReceiptId, "verifierReceiptId", 160);
        verifierId = ReadinessValues.required(verifierId, "verifierId", 128);
        verifiedIssuer = ReadinessValues.required(verifiedIssuer, "verifiedIssuer", 160);
        signatureKeyId = ReadinessValues.required(signatureKeyId, "signatureKeyId", 256);
        signatureAlgorithm = Objects.requireNonNull(signatureAlgorithm, "signatureAlgorithm");
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("expiresAt must follow observedAt");
        }
        if (verifiedAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("verifiedAt must not precede observedAt");
        }
    }
}
