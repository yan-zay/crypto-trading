package com.tj.crypto.governance.approval;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reference to an externally verified MFA challenge; it contains no factor or recovery secret. */
public record MfaAttestation(String subject,
                             String provider,
                             String challengeId,
                             String requestId,
                             ApprovalAction action,
                             String resourceSha256,
                             String governanceSubjectSha256,
                             long verifiedAtMs,
                             long expiresAtMs,
                             String artifactSha256) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_TTL_MS = Duration.ofMinutes(5).toMillis();

    public MfaAttestation {
        subject = required(subject, "subject");
        provider = required(provider, "provider");
        challengeId = required(challengeId, "challengeId");
        requestId = required(requestId, "requestId");
        if (action == null) throw new IllegalArgumentException("action is required");
        resourceSha256 = required(resourceSha256, "resourceSha256").toLowerCase(Locale.ROOT);
        governanceSubjectSha256 = required(
                governanceSubjectSha256, "governanceSubjectSha256").toLowerCase(Locale.ROOT);
        artifactSha256 = required(artifactSha256, "artifactSha256").toLowerCase(Locale.ROOT);
        if (verifiedAtMs <= 0 || expiresAtMs <= verifiedAtMs) {
            throw new IllegalArgumentException("MFA attestation timestamps are invalid");
        }
        if (!SHA_256.matcher(artifactSha256).matches()) {
            throw new IllegalArgumentException("MFA artifactSha256 must be a lowercase SHA-256");
        }
        if (!SHA_256.matcher(resourceSha256).matches()
                || !SHA_256.matcher(governanceSubjectSha256).matches()) {
            throw new IllegalArgumentException(
                    "MFA resource and governance subject must be lowercase SHA-256 values");
        }
        if (expiresAtMs - verifiedAtMs > MAX_TTL_MS) {
            throw new IllegalArgumentException("MFA attestation TTL exceeds five minutes");
        }
    }

    public boolean isActiveAt(long nowMs) {
        return verifiedAtMs <= nowMs && expiresAtMs > nowMs;
    }

    @Override
    public String toString() {
        return "MfaAttestation[subject=" + subject + ", provider=" + provider
                + ", challengeId=<redacted>, requestId=" + requestId + ", action=" + action
                + ", resourceSha256=" + resourceSha256 + ", verifiedAtMs=" + verifiedAtMs
                + ", expiresAtMs=" + expiresAtMs + ", artifactSha256=<redacted>]";
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
