package com.tj.crypto.portfolio.analytics;

import java.util.Locale;
import java.util.regex.Pattern;

/** Auditable completeness boundary supplied by durable OMS, market and FX readers. */
public record TcaInputSnapshot(
        String snapshotId,
        long knowledgeCutoffTimeMs,
        long omsWatermarkTimeMs,
        long marketWatermarkTimeMs,
        long fxWatermarkTimeMs,
        String omsManifestSha256,
        String marketManifestSha256,
        String fxManifestSha256,
        String contentSha256,
        SnapshotCompleteness completeness
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public TcaInputSnapshot {
        snapshotId = required(snapshotId, "snapshotId");
        if (knowledgeCutoffTimeMs <= 0 || omsWatermarkTimeMs <= 0
                || marketWatermarkTimeMs <= 0 || fxWatermarkTimeMs <= 0
                || omsWatermarkTimeMs > knowledgeCutoffTimeMs
                || marketWatermarkTimeMs > knowledgeCutoffTimeMs
                || fxWatermarkTimeMs > knowledgeCutoffTimeMs) {
            throw new IllegalArgumentException("invalid TCA knowledge cutoff or source watermark");
        }
        omsManifestSha256 = sha(omsManifestSha256, "omsManifestSha256");
        marketManifestSha256 = sha(marketManifestSha256, "marketManifestSha256");
        fxManifestSha256 = sha(fxManifestSha256, "fxManifestSha256");
        contentSha256 = sha(contentSha256, "contentSha256");
        if (completeness == null) throw new IllegalArgumentException("completeness is required");
    }

    public void requireCompleteFor(long analysisEndTimeMs, long requestedKnowledgeCutoffTimeMs) {
        if (completeness != SnapshotCompleteness.COMPLETE) {
            throw new IllegalArgumentException("TCA input snapshot is NOT_VERIFIED/incomplete");
        }
        if (requestedKnowledgeCutoffTimeMs != knowledgeCutoffTimeMs) {
            throw new IllegalArgumentException("TCA knowledge cutoff disagrees with input snapshot");
        }
        if (analysisEndTimeMs > omsWatermarkTimeMs
                || analysisEndTimeMs > marketWatermarkTimeMs
                || analysisEndTimeMs > fxWatermarkTimeMs) {
            throw new IllegalArgumentException("TCA source watermarks do not cover analysis end");
        }
    }

    private static String sha(String value, String field) {
        String normalized = required(value, field).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
