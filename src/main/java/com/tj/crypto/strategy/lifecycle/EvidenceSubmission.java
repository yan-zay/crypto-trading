package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;

public record EvidenceSubmission(
        StrategyEvidenceType type,
        EvidenceOutcome outcome,
        byte[] content,
        String evidenceSha256,
        String evidenceUri,
        Instant capturedAt,
        Instant expiresAt) {

    public EvidenceSubmission {
        if (type == null || outcome == null) throw new IllegalArgumentException("evidence type and outcome are required");
        if (content == null || content.length == 0) throw new IllegalArgumentException("evidence content is required");
        content = content.clone();
        Hashes.requireSha256(evidenceSha256, "evidenceSha256");
        if (evidenceUri == null || evidenceUri.isBlank()) throw new IllegalArgumentException("evidenceUri is required");
        if (capturedAt == null || expiresAt == null || !expiresAt.isAfter(capturedAt)) {
            throw new IllegalArgumentException("evidence requires a future expiry");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public boolean contentAddressMatches() {
        return java.security.MessageDigest.isEqual(
                evidenceSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                Hashes.sha256(content).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
