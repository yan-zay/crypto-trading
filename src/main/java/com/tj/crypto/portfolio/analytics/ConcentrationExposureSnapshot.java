package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Content-bound same-currency point-in-time exposure weights. */
public record ConcentrationExposureSnapshot(
        String snapshotId, long asOfTimeMs, String valuationCurrency,
        String sourceManifestSha256, String contentSha256,
        SnapshotCompleteness completeness, Map<String, BigDecimal> absoluteExposures
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ConcentrationExposureSnapshot {
        if (snapshotId == null || snapshotId.isBlank() || asOfTimeMs <= 0) {
            throw new IllegalArgumentException("snapshot id/time is required");
        }
        snapshotId = snapshotId.trim();
        if (valuationCurrency == null || valuationCurrency.isBlank()) throw new IllegalArgumentException("currency is required");
        valuationCurrency = valuationCurrency.trim().toUpperCase(Locale.ROOT);
        sourceManifestSha256 = sha(sourceManifestSha256);
        contentSha256 = sha(contentSha256);
        if (completeness == null) throw new IllegalArgumentException("completeness is required");
        TreeMap<String, BigDecimal> copy = new TreeMap<>();
        absoluteExposures.forEach((id, value) -> {
            if (id == null || id.isBlank() || value == null || value.signum() < 0) {
                throw new IllegalArgumentException("invalid concentration exposure fact");
            }
            copy.put(id.trim(), value.stripTrailingZeros());
        });
        absoluteExposures = Collections.unmodifiableMap(new LinkedHashMap<>(copy));
        if (!contentSha256.equals(digest(snapshotId, asOfTimeMs, valuationCurrency,
                sourceManifestSha256, completeness, absoluteExposures))) {
            throw new IllegalArgumentException("concentration content digest mismatch");
        }
    }

    public static ConcentrationExposureSnapshot create(String id, long asOf, String currency,
                                                        String sourceManifest,
                                                        SnapshotCompleteness completeness,
                                                        Map<String, BigDecimal> exposures) {
        TreeMap<String, BigDecimal> normalized = new TreeMap<>();
        exposures.forEach((key, value) -> normalized.put(key.trim(), value.stripTrailingZeros()));
        String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);
        String manifest = sha(sourceManifest);
        return new ConcentrationExposureSnapshot(id, asOf, normalizedCurrency, manifest,
                digest(id.trim(), asOf, normalizedCurrency, manifest, completeness, normalized),
                completeness, normalized);
    }

    public void requireComplete() {
        if (completeness != SnapshotCompleteness.COMPLETE) throw new IllegalArgumentException("exposure snapshot is incomplete");
    }

    private static String digest(String id, long asOf, String currency, String source,
                                 SnapshotCompleteness completeness, Map<String, BigDecimal> values) {
        StringBuilder text = new StringBuilder(id).append('|').append(asOf).append('|')
                .append(currency).append('|').append(source).append('|').append(completeness);
        values.forEach((key, value) -> text.append('|').append(key).append('=').append(value.toPlainString()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) throw new IllegalArgumentException("manifest must be SHA-256");
        return normalized;
    }
}
