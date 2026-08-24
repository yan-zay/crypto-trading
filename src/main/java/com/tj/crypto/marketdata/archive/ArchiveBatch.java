package com.tj.crypto.marketdata.archive;

import com.tj.crypto.marketdata.model.ArchivableMarketData;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/** 一次不可变、单分区的归档请求。 */
public record ArchiveBatch(
        String datasetId,
        String idempotencyKey,
        String sourceUri,
        String immutableSourceSnapshotId,
        List<ArchivableMarketData> events
) {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_EVENTS = 1_000_000;

    public ArchiveBatch {
        datasetId = safeId(datasetId, "datasetId");
        idempotencyKey = safeId(idempotencyKey, "idempotencyKey");
        sourceUri = immutableSourceUri(sourceUri);
        immutableSourceSnapshotId = safeId(immutableSourceSnapshotId, "immutableSourceSnapshotId");
        if (events == null || events.isEmpty()) {
            throw new ArchiveValidationException("EMPTY_BATCH", "events must not be empty");
        }
        if (events.size() > MAX_EVENTS) {
            throw new ArchiveValidationException("BATCH_TOO_LARGE", "events must not exceed " + MAX_EVENTS);
        }
        events = List.copyOf(events);
    }

    private static String safeId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value.trim()).matches()) {
            throw new ArchiveValidationException("INVALID_ID", field + " must be a safe 1-128 character identifier");
        }
        return value.trim();
    }

    private static String immutableSourceUri(String value) {
        if (value == null || value.isBlank()) {
            throw new ArchiveValidationException("INVALID_SOURCE_URI", "sourceUri is required");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException error) {
            throw new ArchiveValidationException("INVALID_SOURCE_URI", "sourceUri is invalid", error);
        }
        boolean publicHttps = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        boolean internalJournalUrn = "urn".equalsIgnoreCase(uri.getScheme())
                && uri.getSchemeSpecificPart() != null
                && uri.getSchemeSpecificPart().startsWith("crypto-trading:market-journal:");
        if ((!publicHttps && !internalJournalUrn) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ArchiveValidationException("UNSAFE_SOURCE_URI",
                    "sourceUri must be a credential-free HTTPS locator or crypto-trading market-journal URN");
        }
        return uri.normalize().toASCIIString();
    }
}
