package com.tj.crypto.research.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Strict checksum, schema, ordering, duplicate and gap verifier for canonical finalized bars. */
public final class ImmutableBarDatasetVerifier {
    public static final String HEADER = "timestamp,open,high,low,close,volume,quoteVolume";
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private final ImmutableArtifactIO artifactIO;

    public ImmutableBarDatasetVerifier() {
        this(new ImmutableArtifactIO());
    }

    public ImmutableBarDatasetVerifier(ImmutableArtifactIO artifactIO) {
        this.artifactIO = artifactIO;
    }

    public VerifiedBarDataset verify(Path manifestPath) {
        Path manifestArtifact = ImmutableArtifactIO.regularNonSymlink(manifestPath);
        DatasetManifest manifest = artifactIO.read(manifestArtifact, DatasetManifest.class);
        Path directory = manifestArtifact.getParent();
        Path dataPath = directory.resolve(manifest.dataFile()).normalize();
        if (!dataPath.getParent().equals(directory)
                || Files.isSymbolicLink(dataPath)
                || !Files.isRegularFile(dataPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new DatasetValidationException("INVALID_DATA_PATH",
                    "manifest dataFile must resolve to a regular non-symlink sibling");
        }
        String actualDataHash = ImmutableArtifactIO.sha256(dataPath);
        if (!manifest.dataSha256().equals(actualDataHash)) {
            throw new DatasetValidationException("CHECKSUM_MISMATCH",
                    "data SHA-256 does not match the immutable manifest");
        }
        List<CanonicalBarRow> rows = parseAndValidate(dataPath, manifest);
        return new VerifiedBarDataset(manifest, ImmutableArtifactIO.sha256(manifestArtifact),
                manifestArtifact, dataPath, rows);
    }

    private List<CanonicalBarRow> parseAndValidate(Path dataPath, DatasetManifest manifest) {
        List<CanonicalBarRow> rows = new ArrayList<>(Math.toIntExact(manifest.rowCount()));
        try (BufferedReader reader = Files.newBufferedReader(dataPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new DatasetValidationException("SCHEMA_MISMATCH", "CSV header must exactly equal: " + HEADER);
            }
            String line;
            long dataRow = 0;
            long previousTimestamp = -1;
            while ((line = reader.readLine()) != null) {
                dataRow++;
                if (line.isEmpty()) {
                    throw new DatasetValidationException("BLANK_ROW", dataRow, "blank CSV rows are forbidden");
                }
                CanonicalBarRow row = parseRow(line, dataRow);
                if (dataRow == 1 && row.timestamp() != manifest.startInclusiveEpochMillis()) {
                    throw new DatasetValidationException("RANGE_START_MISMATCH", dataRow,
                            "first timestamp does not equal manifest start");
                }
                if (previousTimestamp >= 0) {
                    if (row.timestamp() == previousTimestamp) {
                        throw new DatasetValidationException("DUPLICATE_TIMESTAMP", dataRow,
                                "duplicate bar timestamp: " + row.timestamp());
                    }
                    if (row.timestamp() < previousTimestamp) {
                        throw new DatasetValidationException("OUT_OF_ORDER", dataRow,
                                "bar timestamps must be strictly ascending");
                    }
                    long delta = row.timestamp() - previousTimestamp;
                    if (delta != manifest.instrument().timeframe().getMillis()) {
                        throw new DatasetValidationException("TIMEFRAME_GAP", dataRow,
                                "expected interval " + manifest.instrument().timeframe().getMillis()
                                        + "ms but found " + delta + "ms");
                    }
                }
                previousTimestamp = row.timestamp();
                rows.add(row);
                if (dataRow > manifest.rowCount()) {
                    throw new DatasetValidationException("ROW_COUNT_MISMATCH", dataRow,
                            "data contains more rows than manifest");
                }
            }
        } catch (DatasetValidationException error) {
            throw error;
        } catch (IOException error) {
            throw new DatasetValidationException("DATA_READ_FAILED", "Cannot read canonical CSV: " + error.getMessage());
        }
        if (rows.size() != manifest.rowCount()) {
            throw new DatasetValidationException("ROW_COUNT_MISMATCH",
                    "manifest declares " + manifest.rowCount() + " rows but data has " + rows.size());
        }
        if (rows.get(rows.size() - 1).timestamp() != manifest.endInclusiveEpochMillis()) {
            throw new DatasetValidationException("RANGE_END_MISMATCH", rows.size(),
                    "last timestamp does not equal manifest end");
        }
        return List.copyOf(rows);
    }

    private CanonicalBarRow parseRow(String line, long rowNumber) {
        String[] fields = line.split(",", -1);
        if (fields.length != 7) {
            throw new DatasetValidationException("SCHEMA_MISMATCH", rowNumber,
                    "each canonical CSV row must contain exactly seven unquoted fields");
        }
        for (String field : fields) {
            if (!field.equals(field.trim())) {
                throw new DatasetValidationException("NON_CANONICAL_VALUE", rowNumber,
                        "leading or trailing whitespace is forbidden");
            }
        }
        try {
            if (!INTEGER.matcher(fields[0]).matches()) throw new NumberFormatException("timestamp");
            long timestamp = Long.parseLong(fields[0]);
            BigDecimal open = decimal(fields[1]);
            BigDecimal high = decimal(fields[2]);
            BigDecimal low = decimal(fields[3]);
            BigDecimal close = decimal(fields[4]);
            BigDecimal volume = decimal(fields[5]);
            BigDecimal quoteVolume = decimal(fields[6]);
            validateMarketValues(rowNumber, open, high, low, close, volume, quoteVolume);
            return new CanonicalBarRow(timestamp, open, high, low, close, volume, quoteVolume);
        } catch (DatasetValidationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new DatasetValidationException("NON_CANONICAL_VALUE", rowNumber,
                    "timestamp and numeric values must use canonical base-10 notation");
        }
    }

    private BigDecimal decimal(String value) {
        if (!DECIMAL.matcher(value).matches()) throw new NumberFormatException(value);
        return new BigDecimal(value);
    }

    private void validateMarketValues(long row, BigDecimal open, BigDecimal high, BigDecimal low,
                                      BigDecimal close, BigDecimal volume, BigDecimal quoteVolume) {
        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0) {
            throw new DatasetValidationException("INVALID_OHLC", row, "OHLC prices must be positive");
        }
        if (high.compareTo(open) < 0 || high.compareTo(low) < 0 || high.compareTo(close) < 0
                || low.compareTo(open) > 0 || low.compareTo(high) > 0 || low.compareTo(close) > 0) {
            throw new DatasetValidationException("INVALID_OHLC", row, "high/low do not bound open and close");
        }
        if (volume.signum() < 0 || quoteVolume.signum() < 0) {
            throw new DatasetValidationException("INVALID_VOLUME", row, "volume must be non-negative");
        }
    }
}
