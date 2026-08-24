package com.tj.crypto.governance.evidence;

import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Append-only reference to evidence produced outside the trading process.
 *
 * <p>The record stores only an artifact reference and checksum, never credentials or secret
 * material. A VERIFIED declaration is still an attestation: the referenced artifact must be
 * independently retained and reviewed.</p>
 */
public record ControlEvidence(
        String evidenceId,
        ControlKind kind,
        String scope,
        String environment,
        String subjectSha256,
        String policyVersion,
        EvidenceStatus status,
        String owner,
        String issuer,
        long startedAtMs,
        long completedAtMs,
        long expiresAtMs,
        long registeredAtMs,
        String artifactUri,
        String artifactSha256,
        String predecessorEvidenceId,
        String notes) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ControlEvidence {
        evidenceId = required(evidenceId, "evidenceId", 96);
        kind = Objects.requireNonNull(kind, "kind");
        scope = required(scope, "scope", 128);
        environment = required(environment, "environment", 32).toUpperCase(Locale.ROOT);
        subjectSha256 = required(subjectSha256, "subjectSha256", 64).toLowerCase(Locale.ROOT);
        policyVersion = required(policyVersion, "policyVersion", 96);
        status = Objects.requireNonNull(status, "status");
        owner = required(owner, "owner", 96);
        issuer = optional(issuer, "issuer", 128);
        artifactUri = optional(artifactUri, "artifactUri", 512);
        artifactSha256 = optional(artifactSha256, "artifactSha256", 64).toLowerCase(Locale.ROOT);
        predecessorEvidenceId = optional(predecessorEvidenceId, "predecessorEvidenceId", 96);
        notes = optional(notes, "notes", 1000);

        if (startedAtMs < 0 || completedAtMs < 0 || expiresAtMs < 0 || registeredAtMs <= 0) {
            throw new IllegalArgumentException("evidence timestamps must be non-negative and registeredAtMs positive");
        }
        if (completedAtMs > 0 && completedAtMs < startedAtMs) {
            throw new IllegalArgumentException("completedAtMs must not precede startedAtMs");
        }
        if (completedAtMs > 0 && registeredAtMs < completedAtMs) {
            throw new IllegalArgumentException("registeredAtMs must not precede completedAtMs");
        }
        if (expiresAtMs > 0 && completedAtMs > 0 && expiresAtMs <= completedAtMs) {
            throw new IllegalArgumentException("expiresAtMs must follow completedAtMs");
        }
        if (!artifactSha256.isEmpty() && !SHA_256.matcher(artifactSha256).matches()) {
            throw new IllegalArgumentException("artifactSha256 must be a lowercase SHA-256");
        }
        if (!SHA_256.matcher(subjectSha256).matches()) {
            throw new IllegalArgumentException("subjectSha256 must be a lowercase SHA-256");
        }
        if (status == EvidenceStatus.VERIFIED) {
            if (completedAtMs <= 0 || expiresAtMs <= completedAtMs) {
                throw new IllegalArgumentException("VERIFIED evidence requires completion and expiry timestamps");
            }
            if (issuer.isEmpty() || artifactUri.isEmpty() || artifactSha256.isEmpty()) {
                throw new IllegalArgumentException("VERIFIED evidence requires issuer, artifact URI and SHA-256");
            }
            requireOpaqueArtifactReference(artifactUri);
        }
        if (status == EvidenceStatus.REVOKED) {
            if (predecessorEvidenceId.isEmpty()) {
                throw new IllegalArgumentException("REVOKED evidence must reference its predecessor");
            }
            if (completedAtMs <= 0 || issuer.isEmpty() || artifactUri.isEmpty()
                    || artifactSha256.isEmpty()) {
                throw new IllegalArgumentException(
                        "REVOKED evidence requires a signed external revocation artifact");
            }
            requireOpaqueArtifactReference(artifactUri);
        }
    }

    public boolean isActiveAt(long nowMs) {
        return status == EvidenceStatus.VERIFIED
                && completedAtMs <= nowMs
                && registeredAtMs <= nowMs
                && expiresAtMs > nowMs;
    }

    public long observedDurationMs() {
        return completedAtMs <= startedAtMs ? 0L : completedAtMs - startedAtMs;
    }

    @Override
    public String toString() {
        return "ControlEvidence[evidenceId=" + evidenceId + ", kind=" + kind
                + ", scope=" + scope + ", environment=" + environment
                + ", subjectSha256=" + subjectSha256 + ", policyVersion=" + policyVersion
                + ", status=" + status + ", owner=" + owner + ", issuer=" + issuer
                + ", artifact=<redacted>, predecessorEvidenceId=" + predecessorEvidenceId + "]";
    }

    private static void requireOpaqueArtifactReference(String raw) {
        try {
            URI uri = URI.create(raw);
            boolean safe = "evidence".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawPath() != null && !uri.getRawPath().isBlank()
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && uri.getPort() == -1;
            if (!safe) throw new IllegalArgumentException("unsafe reference");
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "artifactUri must be an opaque evidence://store/object reference", invalid);
        }
    }

    private static String required(String value, String name, int maxLength) {
        String normalized = optional(value, name, maxLength);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String optional(String value, String name, int maxLength) {
        String normalized = value == null ? ""
                : Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        if (normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return normalized;
    }
}
