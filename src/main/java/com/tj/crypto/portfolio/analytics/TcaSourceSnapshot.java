package com.tj.crypto.portfolio.analytics;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable lineage boundary for one source participating in a TCA input snapshot. */
public record TcaSourceSnapshot(
        long watermarkTimeMs,
        String sourceManifestSha256,
        SnapshotCompleteness completeness
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public TcaSourceSnapshot {
        if (watermarkTimeMs <= 0) {
            throw new IllegalArgumentException("watermarkTimeMs must be positive");
        }
        sourceManifestSha256 = required(sourceManifestSha256, "sourceManifestSha256")
                .toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(sourceManifestSha256).matches()) {
            throw new IllegalArgumentException(
                    "sourceManifestSha256 must be 64 lowercase hex characters");
        }
        Objects.requireNonNull(completeness, "completeness");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
