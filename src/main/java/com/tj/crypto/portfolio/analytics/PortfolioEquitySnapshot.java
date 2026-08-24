package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Content-bound point-in-time account equity used by stress analysis. */
public record PortfolioEquitySnapshot(
        String accountId,
        long asOfTimeMs,
        long observedAtMs,
        String valuationCurrency,
        BigDecimal equity,
        String ledgerManifestSha256,
        String contentSha256,
        SnapshotCompleteness completeness
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public PortfolioEquitySnapshot {
        accountId = required(accountId, "accountId");
        valuationCurrency = required(valuationCurrency, "valuationCurrency").toUpperCase(Locale.ROOT);
        if (asOfTimeMs <= 0 || observedAtMs <= 0 || observedAtMs > asOfTimeMs) {
            throw new IllegalArgumentException("equity snapshot is not point-in-time");
        }
        if (equity == null || equity.signum() <= 0) throw new IllegalArgumentException("equity must be positive");
        equity = equity.stripTrailingZeros();
        ledgerManifestSha256 = sha(ledgerManifestSha256, "ledgerManifestSha256");
        contentSha256 = sha(contentSha256, "contentSha256");
        if (completeness == null) throw new IllegalArgumentException("completeness is required");
        String expected = digest(accountId, asOfTimeMs, observedAtMs, valuationCurrency,
                equity, ledgerManifestSha256, completeness);
        if (!contentSha256.equals(expected)) throw new IllegalArgumentException("equity content digest mismatch");
    }

    public static PortfolioEquitySnapshot create(String accountId, long asOfTimeMs, long observedAtMs,
                                                 String valuationCurrency, BigDecimal equity,
                                                 String ledgerManifestSha256,
                                                 SnapshotCompleteness completeness) {
        String currency = required(valuationCurrency, "valuationCurrency").toUpperCase(Locale.ROOT);
        BigDecimal normalized = equity.stripTrailingZeros();
        String ledger = sha(ledgerManifestSha256, "ledgerManifestSha256");
        return new PortfolioEquitySnapshot(accountId, asOfTimeMs, observedAtMs, currency, normalized,
                ledger, digest(accountId.trim(), asOfTimeMs, observedAtMs, currency, normalized,
                ledger, completeness), completeness);
    }

    public void requireComplete() {
        if (completeness != SnapshotCompleteness.COMPLETE) {
            throw new IllegalArgumentException("equity snapshot is incomplete");
        }
    }

    private static String digest(String accountId, long asOf, long observed, String currency,
                                 BigDecimal equity, String manifest, SnapshotCompleteness completeness) {
        String value = accountId + '|' + asOf + '|' + observed + '|' + currency + '|'
                + equity.toPlainString() + '|' + manifest + '|' + completeness;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sha(String value, String field) {
        String normalized = required(value, field).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
