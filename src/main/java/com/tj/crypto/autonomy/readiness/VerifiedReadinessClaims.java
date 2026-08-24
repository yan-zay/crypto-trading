package com.tj.crypto.autonomy.readiness;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Common signed claims plus one closed control-specific payload.
 *
 * <p>Instances are produced only after a trust adapter has fetched immutable bytes, recomputed
 * their digest, validated the exact schema/audience, verified signature and issuer revocation,
 * and mapped the artifact into {@link VerifiedControlPayload}.</p>
 */
public record VerifiedReadinessClaims(
        String evidenceId,
        AutonomyLevel autonomyLevel,
        String schemaVersion,
        String audience,
        String subjectSha256,
        String readinessSubjectSha256,
        String releaseId,
        String releaseArtifactSha256,
        String accountScope,
        String environment,
        String policyVersion,
        String policyArtifactSha256,
        String agentBundleSha256,
        String legalScopeSha256,
        String envelopeSha256,
        URI artifactUri,
        Instant observedAt,
        Instant expiresAt,
        VerificationOutcome outcome,
        String contentSha256,
        String verifierId,
        String verifierReceiptId,
        String verifiedIssuer,
        String signatureKeyId,
        SignatureAlgorithm signatureAlgorithm,
        Instant verifiedAt,
        VerifiedControlPayload payload) implements EvidenceVerification {

    public VerifiedReadinessClaims {
        evidenceId = ReadinessValues.required(evidenceId, "evidenceId", 128);
        autonomyLevel = Objects.requireNonNull(autonomyLevel, "autonomyLevel");
        schemaVersion = ReadinessValues.required(schemaVersion, "schemaVersion", 160);
        audience = ReadinessValues.required(audience, "audience", 128);
        subjectSha256 = ReadinessValues.hash(subjectSha256, "subjectSha256");
        readinessSubjectSha256 = ReadinessValues.hash(
                readinessSubjectSha256, "readinessSubjectSha256");
        releaseId = ReadinessValues.required(releaseId, "releaseId", 128);
        releaseArtifactSha256 = ReadinessValues.hash(
                releaseArtifactSha256, "releaseArtifactSha256");
        accountScope = ReadinessValues.required(accountScope, "accountScope", 192);
        environment = ReadinessValues.required(environment, "environment", 32)
                .toUpperCase(Locale.ROOT);
        policyVersion = ReadinessValues.required(policyVersion, "policyVersion", 96);
        policyArtifactSha256 = ReadinessValues.hash(
                policyArtifactSha256, "policyArtifactSha256");
        agentBundleSha256 = ReadinessValues.hash(agentBundleSha256, "agentBundleSha256");
        legalScopeSha256 = ReadinessValues.hash(legalScopeSha256, "legalScopeSha256");
        envelopeSha256 = ReadinessValues.hash(envelopeSha256, "envelopeSha256");
        artifactUri = Objects.requireNonNull(artifactUri, "artifactUri");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        outcome = Objects.requireNonNull(outcome, "outcome");
        contentSha256 = ReadinessValues.hash(contentSha256, "contentSha256");
        verifierId = ReadinessValues.required(verifierId, "verifierId", 128);
        verifierReceiptId = ReadinessValues.required(
                verifierReceiptId, "verifierReceiptId", 160);
        verifiedIssuer = ReadinessValues.required(verifiedIssuer, "verifiedIssuer", 160);
        signatureKeyId = ReadinessValues.required(signatureKeyId, "signatureKeyId", 256);
        signatureAlgorithm = Objects.requireNonNull(signatureAlgorithm, "signatureAlgorithm");
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        payload = Objects.requireNonNull(payload, "payload");
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("expiresAt must follow observedAt");
        }
        if (verifiedAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("verifiedAt must not precede observedAt");
        }
    }

    public LongTermControl control() {
        return payload.control();
    }
}
