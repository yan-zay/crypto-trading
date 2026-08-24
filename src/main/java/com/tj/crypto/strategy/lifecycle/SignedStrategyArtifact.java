package com.tj.crypto.strategy.lifecycle;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** A content-addressed strategy artifact whose identity and TTL are covered by its signature. */
public record SignedStrategyArtifact(
        String strategyName,
        String artifactVersion,
        byte[] content,
        String artifactSha256,
        String mediaType,
        String artifactUri,
        String signatureAlgorithm,
        String signingKeyId,
        String signatureBase64,
        Instant signedAt,
        Instant expiresAt,
        String submittedBy,
        Instant submittedAt,
        String researchRegistrationId,
        String researchRegistrationSha256) {

    public SignedStrategyArtifact {
        requireText(strategyName, "strategyName");
        requireText(artifactVersion, "artifactVersion");
        if (content == null || content.length == 0) throw new IllegalArgumentException("artifact content is required");
        content = content.clone();
        Hashes.requireSha256(artifactSha256, "artifactSha256");
        requireText(mediaType, "mediaType");
        requireText(signatureAlgorithm, "signatureAlgorithm");
        requireText(signingKeyId, "signingKeyId");
        requireText(signatureBase64, "signatureBase64");
        try {
            Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("signatureBase64 is invalid", invalid);
        }
        if (signedAt == null || expiresAt == null || !expiresAt.isAfter(signedAt)) {
            throw new IllegalArgumentException("artifact signature requires a future expiry");
        }
        requireText(submittedBy, "submittedBy");
        if (submittedAt == null) throw new IllegalArgumentException("submittedAt is required");
        if ((researchRegistrationId == null) != (researchRegistrationSha256 == null)) {
            throw new IllegalArgumentException("research registration id and digest must be supplied together");
        }
        if (researchRegistrationSha256 != null) {
            Hashes.requireSha256(researchRegistrationSha256, "researchRegistrationSha256");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public boolean contentAddressMatches() {
        return MessageDigestSupport.constantTimeEquals(artifactSha256, Hashes.sha256(content));
    }

    /**
     * Domain-separated bytes bind content identity, provenance, trust key, and TTL. In particular,
     * research registry metadata cannot be swapped while reusing a signature over the same code.
     */
    public byte[] signaturePayload() {
        String canonical = "CRYPTO_TRADING_STRATEGY_ARTIFACT_V2\n"
                + strategyName + "\n" + artifactVersion + "\n" + artifactSha256 + "\n"
                + mediaType + "\n" + normalized(artifactUri) + "\n"
                + signatureAlgorithm + "\n" + signingKeyId + "\n"
                + signedAt.toEpochMilli() + "\n" + expiresAt.toEpochMilli() + "\n"
                + normalized(researchRegistrationId) + "\n"
                + normalized(researchRegistrationSha256) + "\n";
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static String normalized(String value) {
        return value == null ? "" : value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static final class MessageDigestSupport {
        private static boolean constantTimeEquals(String left, String right) {
            return java.security.MessageDigest.isEqual(
                    left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
