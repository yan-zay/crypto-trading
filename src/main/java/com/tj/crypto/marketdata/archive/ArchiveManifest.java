package com.tj.crypto.marketdata.archive;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;

import java.util.Locale;
import java.util.regex.Pattern;

/** Parquet 对象的不可变 manifest。 */
public record ArchiveManifest(
        String manifestVersion,
        String datasetId,
        String idempotencyKey,
        String schemaName,
        String schemaVersion,
        String schemaFingerprintSha256,
        ArchiveEventType eventType,
        Exchange source,
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String eventDateUtc,
        String dataFile,
        String dataSha256,
        String requestSha256,
        long rowCount,
        long byteSize,
        long startInclusiveEpochMillis,
        long endExclusiveEpochMillis,
        String sourceUri,
        String immutableSourceSnapshotId,
        long createdAtEpochMillis,
        String writerVersion
) {
    public static final String CURRENT_MANIFEST_VERSION = "1";
    public static final String SCHEMA_NAME = "canonical-market-event-parquet";
    public static final String CURRENT_SCHEMA_VERSION = "1";
    public static final String DATA_FILE = "events.parquet";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public ArchiveManifest {
        manifestVersion = required(manifestVersion, "manifestVersion");
        datasetId = required(datasetId, "datasetId");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        schemaName = required(schemaName, "schemaName");
        schemaVersion = required(schemaVersion, "schemaVersion");
        schemaFingerprintSha256 = hash(schemaFingerprintSha256, "schemaFingerprintSha256");
        dataSha256 = hash(dataSha256, "dataSha256");
        requestSha256 = hash(requestSha256, "requestSha256");
        symbol = required(symbol, "symbol").toUpperCase(Locale.ROOT);
        eventDateUtc = required(eventDateUtc, "eventDateUtc");
        dataFile = required(dataFile, "dataFile");
        sourceUri = required(sourceUri, "sourceUri");
        immutableSourceSnapshotId = required(immutableSourceSnapshotId, "immutableSourceSnapshotId");
        writerVersion = required(writerVersion, "writerVersion");
        if (!CURRENT_MANIFEST_VERSION.equals(manifestVersion)
                || !SCHEMA_NAME.equals(schemaName)
                || !CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported archive manifest or schema version");
        }
        if (eventType == null || source == null || exchange == null || marketType == null) {
            throw new IllegalArgumentException("event type and instrument identity are required");
        }
        if (!DATE.matcher(eventDateUtc).matches()) throw new IllegalArgumentException("eventDateUtc is invalid");
        if (!DATA_FILE.equals(dataFile)) throw new IllegalArgumentException("unsupported dataFile");
        if (rowCount <= 0 || byteSize <= 0) throw new IllegalArgumentException("rowCount and byteSize must be positive");
        if (startInclusiveEpochMillis < 0 || endExclusiveEpochMillis <= startInclusiveEpochMillis) {
            throw new IllegalArgumentException("archive time range must be a non-empty half-open interval");
        }
        if (createdAtEpochMillis <= 0) throw new IllegalArgumentException("createdAtEpochMillis must be positive");
    }

    private static String hash(String value, String field) {
        String normalized = required(value, field).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
