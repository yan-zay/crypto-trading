package com.tj.crypto.portfolio.analytics;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Explicit point-in-time portfolio input boundary.
 *
 * <p>An empty exposure list is meaningful only when the caller declares the complete account
 * scope and supplies a content-addressed source manifest. An analyzer still rejects an
 * {@link SnapshotCompleteness#INCOMPLETE} snapshot.
 */
public record PortfolioExposureSnapshot(
        String snapshotId,
        long asOfTimeMs,
        String valuationCurrency,
        Set<String> accountIds,
        SnapshotCompleteness completeness,
        String sourceManifestSha256,
        List<PortfolioExposure> exposures
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public PortfolioExposureSnapshot {
        snapshotId = required(snapshotId, "snapshotId");
        if (asOfTimeMs <= 0) throw new IllegalArgumentException("asOfTimeMs must be positive");
        valuationCurrency = required(valuationCurrency, "valuationCurrency").toUpperCase(Locale.ROOT);
        Objects.requireNonNull(accountIds, "accountIds");
        if (accountIds.isEmpty()) throw new IllegalArgumentException("accountIds must not be empty");
        TreeSet<String> normalizedAccounts = new TreeSet<>();
        for (String accountId : accountIds) {
            if (!normalizedAccounts.add(required(accountId, "accountId"))) {
                throw new IllegalArgumentException("duplicate normalized accountId");
            }
        }
        accountIds = Set.copyOf(new LinkedHashSet<>(normalizedAccounts));
        Objects.requireNonNull(completeness, "completeness");
        sourceManifestSha256 = required(sourceManifestSha256, "sourceManifestSha256")
                .toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(sourceManifestSha256).matches()) {
            throw new IllegalArgumentException("sourceManifestSha256 must be 64 lowercase hex characters");
        }
        exposures = List.copyOf(Objects.requireNonNull(exposures, "exposures"));
        for (PortfolioExposure exposure : exposures) {
            Objects.requireNonNull(exposure, "exposure");
            if (!accountIds.contains(exposure.accountId())) {
                throw new IllegalArgumentException("exposure account is outside declared snapshot scope: "
                        + exposure.accountId());
            }
            if (exposure.valuationModel() != ExposureValuationModel.LINEAR_QUOTE_SETTLED) {
                throw new IllegalArgumentException("unsupported exposure valuation model");
            }
            if (exposure.completeness() != SnapshotCompleteness.COMPLETE
                    || !valuationCurrency.equals(exposure.quoteCurrency())) {
                throw new IllegalArgumentException("exposure currency/completeness disagrees with snapshot");
            }
            if (exposure.markEventTimeMs() > asOfTimeMs || exposure.markObservedAtMs() > asOfTimeMs) {
                throw new IllegalArgumentException("exposure mark is not point-in-time at snapshot boundary");
            }
        }
        String expectedManifest = contentSha256(snapshotId, asOfTimeMs, valuationCurrency,
                accountIds, completeness, exposures);
        if (!sourceManifestSha256.equals(expectedManifest)) {
            throw new IllegalArgumentException("sourceManifestSha256 does not bind snapshot contents");
        }
    }

    public static PortfolioExposureSnapshot create(String snapshotId, long asOfTimeMs,
                                                    String valuationCurrency, Set<String> accountIds,
                                                    SnapshotCompleteness completeness,
                                                    List<PortfolioExposure> exposures) {
        String normalizedCurrency = required(valuationCurrency, "valuationCurrency").toUpperCase(Locale.ROOT);
        Set<String> normalizedAccounts = Set.copyOf(new TreeSet<>(accountIds));
        String digest = contentSha256(snapshotId.trim(), asOfTimeMs, normalizedCurrency,
                normalizedAccounts, completeness, exposures);
        return new PortfolioExposureSnapshot(snapshotId, asOfTimeMs, normalizedCurrency,
                normalizedAccounts, completeness, digest, exposures);
    }

    public void requireComplete() {
        if (completeness != SnapshotCompleteness.COMPLETE) {
            throw new IllegalArgumentException("portfolio exposure snapshot is incomplete");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String contentSha256(String snapshotId, long asOfTimeMs, String valuationCurrency,
                                        Set<String> accountIds, SnapshotCompleteness completeness,
                                        List<PortfolioExposure> exposures) {
        StringBuilder canonical = new StringBuilder(snapshotId).append('|').append(asOfTimeMs)
                .append('|').append(valuationCurrency).append('|').append(completeness);
        accountIds.stream().sorted().forEach(value -> canonical.append('|').append(value));
        for (PortfolioExposure value : exposures) {
            canonical.append('|').append(value.accountId()).append('|').append(value.strategyId())
                    .append('|').append(value.instrumentId().value()).append('|').append(value.direction())
                    .append('|').append(value.quantity().toPlainString()).append('|')
                    .append(value.markPrice().toPlainString()).append('|')
                    .append(value.contractMultiplier().toPlainString()).append('|')
                    .append(value.quoteCurrency()).append('|').append(value.markEventTimeMs())
                    .append('|').append(value.markObservedAtMs()).append('|')
                    .append(value.sourceManifestSha256()).append('|').append(value.completeness());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
