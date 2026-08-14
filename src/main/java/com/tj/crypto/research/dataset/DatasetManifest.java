package com.tj.crypto.research.dataset;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Immutable identity and byte-level contract for one finalized-bar research artifact.
 * The referenced data file is immutable by convention and by SHA-256 verification.
 */
public record DatasetManifest(
        String manifestVersion,
        String datasetId,
        String schemaName,
        String schemaVersion,
        String dataFile,
        String dataSha256,
        long rowCount,
        long startInclusiveEpochMillis,
        long endInclusiveEpochMillis,
        InstrumentIdentity instrument,
        Provenance provenance
) {
    public static final String CURRENT_MANIFEST_VERSION = "1";
    public static final String FINAL_BAR_SCHEMA = "canonical-finalized-bar-csv";
    public static final String CURRENT_SCHEMA_VERSION = "1";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public DatasetManifest {
        manifestVersion = required(manifestVersion, "manifestVersion");
        datasetId = required(datasetId, "datasetId");
        schemaName = required(schemaName, "schemaName");
        schemaVersion = required(schemaVersion, "schemaVersion");
        dataFile = required(dataFile, "dataFile");
        dataSha256 = required(dataSha256, "dataSha256").toLowerCase(Locale.ROOT);
        if (!CURRENT_MANIFEST_VERSION.equals(manifestVersion)) {
            throw new IllegalArgumentException("unsupported manifestVersion: " + manifestVersion);
        }
        if (!FINAL_BAR_SCHEMA.equals(schemaName) || !CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported finalized-bar schema: " + schemaName + "/" + schemaVersion);
        }
        if (!SAFE_FILE_NAME.matcher(dataFile).matches() || ".".equals(dataFile) || "..".equals(dataFile)) {
            throw new IllegalArgumentException("dataFile must be a safe file name in the manifest directory");
        }
        if (!SHA_256.matcher(dataSha256).matches()) {
            throw new IllegalArgumentException("dataSha256 must be 64 lowercase hexadecimal characters");
        }
        if (rowCount < 2 || rowCount > 5_000_000) {
            throw new IllegalArgumentException("rowCount must be between 2 and 5000000");
        }
        if (instrument == null) throw new IllegalArgumentException("instrument is required");
        if (provenance == null) throw new IllegalArgumentException("provenance is required");
        if (startInclusiveEpochMillis < 0 || endInclusiveEpochMillis < startInclusiveEpochMillis) {
            throw new IllegalArgumentException("dataset time range is invalid");
        }
        long interval = instrument.timeframe().getMillis();
        if (startInclusiveEpochMillis % interval != 0 || endInclusiveEpochMillis % interval != 0) {
            throw new IllegalArgumentException("dataset boundaries must align to timeframe");
        }
        long expectedRows;
        try {
            expectedRows = Math.addExact(Math.floorDiv(
                    Math.subtractExact(endInclusiveEpochMillis, startInclusiveEpochMillis), interval), 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("dataset range overflows row-count calculation", overflow);
        }
        if (rowCount != expectedRows) {
            throw new IllegalArgumentException("rowCount does not cover every timeframe interval in the declared range");
        }
    }

    @JsonIgnore
    public boolean isDeclaredImmutableRealMarketData() {
        return provenance.origin() == DatasetOrigin.REAL_MARKET
                && provenance.immutableSourceSnapshotId() != null
                && !provenance.immutableSourceSnapshotId().isBlank();
    }

    public record InstrumentIdentity(
            Exchange exchange,
            MarketType marketType,
            String symbol,
            String baseAsset,
            String quoteAsset,
            Timeframe timeframe
    ) {
        public InstrumentIdentity {
            if (exchange == null || marketType == null || timeframe == null) {
                throw new IllegalArgumentException("exchange, marketType and timeframe are required");
            }
            symbol = upper(symbol, "symbol");
            baseAsset = upper(baseAsset, "baseAsset");
            quoteAsset = upper(quoteAsset, "quoteAsset");
            if (!symbol.equals(baseAsset + quoteAsset)) {
                throw new IllegalArgumentException("symbol must equal baseAsset + quoteAsset for the current universe");
            }
        }

        private static String upper(String value, String field) {
            return required(value, field).toUpperCase(Locale.ROOT);
        }
    }

    public record Provenance(
            DatasetOrigin origin,
            String sourceUri,
            String immutableSourceSnapshotId,
            long acquiredAtEpochMillis
    ) {
        public Provenance {
            if (origin == null) throw new IllegalArgumentException("provenance origin is required");
            sourceUri = required(sourceUri, "sourceUri");
            immutableSourceSnapshotId = required(immutableSourceSnapshotId, "immutableSourceSnapshotId");
            if (acquiredAtEpochMillis <= 0) {
                throw new IllegalArgumentException("acquiredAtEpochMillis must be positive");
            }
            URI uri;
            try {
                uri = URI.create(sourceUri);
            } catch (IllegalArgumentException invalidUri) {
                throw new IllegalArgumentException("sourceUri is invalid", invalidUri);
            }
            if (!uri.isAbsolute()) throw new IllegalArgumentException("sourceUri must be absolute");
            if (origin == DatasetOrigin.REAL_MARKET && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("REAL_MARKET sourceUri must use HTTPS");
            }
        }
    }

    public enum DatasetOrigin {
        REAL_MARKET,
        SYNTHETIC,
        TEST_FIXTURE
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
