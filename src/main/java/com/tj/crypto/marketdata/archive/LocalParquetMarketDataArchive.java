package com.tj.crypto.marketdata.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.marketdata.model.ArchivableMarketData;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.model.RawMarketMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 本地对象存储语义实现：Hive 风格分区、全局幂等键、SHA-256、manifest-last 和目录原子提交。
 * 所有写入均为 CREATE_NEW；已有对象永不覆盖或原地更新。
 */
public final class LocalParquetMarketDataArchive implements ImmutableMarketDataArchive {
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String POINTER_VERSION = "1";
    private static final Pattern SAFE_PARTITION = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,79}");

    private final Path root;
    private final Path stagingRoot;
    private final Path commitRoot;
    private final Path lockRoot;
    private final Clock clock;
    private final DirectoryDurabilityBarrier directoryDurabilityBarrier;
    private final ObjectMapper json = ArchiveJson.mapper();
    private final ParquetMarketEventCodec codec = new ParquetMarketEventCodec();

    public LocalParquetMarketDataArchive(Path root) {
        this(root, Clock.systemUTC(), DirectoryDurabilityBarrier.strictPlatform());
    }

    LocalParquetMarketDataArchive(Path root, Clock clock) {
        this(root, clock, DirectoryDurabilityBarrier.strictPlatform());
    }

    LocalParquetMarketDataArchive(Path root, Clock clock,
                                  DirectoryDurabilityBarrier directoryDurabilityBarrier) {
        if (root == null || clock == null || directoryDurabilityBarrier == null) {
            throw new IllegalArgumentException("root, clock and directory durability barrier are required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.directoryDurabilityBarrier = directoryDurabilityBarrier;
        this.stagingRoot = this.root.resolve("_staging");
        this.commitRoot = this.root.resolve("_commits");
        this.lockRoot = this.root.resolve("_locks");
        createOwnedDirectory(this.root);
        createOwnedDirectory(stagingRoot);
        createOwnedDirectory(commitRoot);
        createOwnedDirectory(lockRoot);
    }

    @Override
    public ArchiveCommit archive(ArchiveBatch batch) {
        if (batch == null) throw new ArchiveValidationException("MISSING_BATCH", "archive batch is required");
        BatchFacts facts = analyze(batch.events());
        String requestSha256 = requestSha256(batch, facts.type());
        Path pointerPath = commitRoot.resolve(batch.idempotencyKey() + ".json");
        Path lockPath = lockRoot.resolve(batch.idempotencyKey() + ".lock");

        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            if (Files.exists(pointerPath, LinkOption.NOFOLLOW_LINKS)) {
                return fromPointer(pointerPath, batch, requestSha256, true);
            }

            Path target = targetDirectory(batch, facts);
            ArchiveCommit commit;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                commit = existingTarget(target, batch, requestSha256, true);
            } else {
                commit = createTarget(target, batch, facts, requestSha256);
            }
            writePointer(pointerPath, commit, batch, requestSha256);
            return commit;
        } catch (ArchiveValidationException error) {
            throw error;
        } catch (IOException error) {
            throw new ArchiveValidationException("ARCHIVE_COMMIT_FAILED",
                    "cannot commit immutable archive: " + error.getMessage(), error);
        }
    }

    @Override
    public ArchiveVerification verify(Path manifestPath) {
        return verifiedContent(manifestPath).verification();
    }

    @Override
    public List<ArchivableMarketData> read(Path manifestPath) {
        return verifiedContent(manifestPath).events();
    }

    private ArchiveCommit createTarget(Path target, ArchiveBatch batch, BatchFacts facts,
                                       String requestSha256) {
        createOwnedDirectory(target.getParent());
        Path staging = stagingRoot.resolve(batch.idempotencyKey() + "-" + UUID.randomUUID());
        try {
            Files.createDirectory(staging);
            Path data = staging.resolve(ArchiveManifest.DATA_FILE);
            codec.write(data, facts.type(), batch.events());
            forceFile(data);
            directoryDurabilityBarrier.force(staging);

            List<ArchivableMarketData> readBack = codec.read(data, facts.type());
            if (readBack.size() != batch.events().size()) {
                throw new ArchiveValidationException("PARQUET_ROW_COUNT_MISMATCH",
                        "Parquet read-back row count differs from the write request");
            }
            String readBackSha = requestSha256(new ArchiveBatch(batch.datasetId(), batch.idempotencyKey(),
                    batch.sourceUri(), batch.immutableSourceSnapshotId(), readBack), facts.type());
            if (!requestSha256.equals(readBackSha)) {
                throw new ArchiveValidationException("PARQUET_ROUND_TRIP_MISMATCH",
                        "Parquet read-back differs from canonical input facts");
            }

            ArchiveManifest manifest = new ArchiveManifest(
                    ArchiveManifest.CURRENT_MANIFEST_VERSION, batch.datasetId(), batch.idempotencyKey(),
                    ArchiveManifest.SCHEMA_NAME, ArchiveManifest.CURRENT_SCHEMA_VERSION,
                    codec.schemaFingerprint(facts.type()), facts.type(), facts.source(), facts.exchange(),
                    facts.marketType(), facts.symbol(), facts.eventDate().toString(), ArchiveManifest.DATA_FILE,
                    sha256(data), requestSha256, readBack.size(), size(data), facts.startInclusive(),
                    facts.endExclusive(), batch.sourceUri(), batch.immutableSourceSnapshotId(),
                    clock.millis(), ParquetMarketEventCodec.WRITER_VERSION);
            Path manifestPath = staging.resolve(MANIFEST_FILE);
            writeJsonNew(manifestPath, manifest);
            String manifestSha256 = sha256(manifestPath);
            forceFile(manifestPath);
            directoryDurabilityBarrier.force(staging);

            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                directoryDurabilityBarrier.force(target.getParent());
                directoryDurabilityBarrier.force(stagingRoot);
            } catch (AtomicMoveNotSupportedException error) {
                throw new ArchiveValidationException("ATOMIC_MOVE_UNSUPPORTED",
                        "archive root must support same-filesystem atomic directory moves", error);
            } catch (FileAlreadyExistsException raced) {
                return existingTarget(target, batch, requestSha256, true);
            }
            Path committedManifest = target.resolve(MANIFEST_FILE);
            ArchiveVerification verified = verify(committedManifest);
            if (!verified.manifestSha256().equals(manifestSha256)) {
                throw new ArchiveValidationException("MANIFEST_COMMIT_MISMATCH",
                        "committed manifest bytes differ from the staged manifest");
            }
            return new ArchiveCommit(verified.manifest(), committedManifest, target.resolve(ArchiveManifest.DATA_FILE),
                    verified.manifestSha256(), false);
        } catch (ArchiveValidationException error) {
            throw error;
        } catch (IOException error) {
            throw new ArchiveValidationException("ARCHIVE_WRITE_FAILED",
                    "cannot create immutable archive object: " + error.getMessage(), error);
        } finally {
            deleteStaging(staging);
        }
    }

    private ArchiveCommit existingTarget(Path target, ArchiveBatch batch, String requestSha256,
                                          boolean alreadyPresent) {
        directoryDurabilityBarrier.force(target.getParent());
        ArchiveVerification verification = verify(target.resolve(MANIFEST_FILE));
        ArchiveManifest manifest = verification.manifest();
        if (!manifest.datasetId().equals(batch.datasetId())
                || !manifest.idempotencyKey().equals(batch.idempotencyKey())
                || !manifest.requestSha256().equals(requestSha256)) {
            throw new ArchiveIdentityConflictException(
                    "idempotency key already identifies different immutable facts");
        }
        return new ArchiveCommit(manifest, verification.manifestPath(), verification.dataPath(),
                verification.manifestSha256(), alreadyPresent);
    }

    private ArchiveCommit fromPointer(Path pointerPath, ArchiveBatch batch, String requestSha256,
                                      boolean alreadyPresent) {
        directoryDurabilityBarrier.force(pointerPath.getParent());
        CommitPointer pointer = readJson(pointerPath, CommitPointer.class);
        if (!POINTER_VERSION.equals(pointer.pointerVersion())
                || !pointer.idempotencyKey().equals(batch.idempotencyKey())
                || !pointer.datasetId().equals(batch.datasetId())
                || !pointer.requestSha256().equals(requestSha256)) {
            throw new ArchiveIdentityConflictException(
                    "global idempotency key already identifies a different archive request");
        }
        Path manifestPath = root.resolve(pointer.manifestRelativePath()).normalize();
        requireWithinRoot(manifestPath);
        ArchiveVerification verification = verify(manifestPath);
        if (!verification.manifestSha256().equals(pointer.manifestSha256())) {
            throw new ArchiveValidationException("COMMIT_POINTER_MISMATCH",
                    "commit pointer manifest checksum does not match immutable manifest");
        }
        return new ArchiveCommit(verification.manifest(), verification.manifestPath(), verification.dataPath(),
                verification.manifestSha256(), alreadyPresent);
    }

    private void writePointer(Path pointerPath, ArchiveCommit commit, ArchiveBatch batch,
                              String requestSha256) {
        String relative = root.relativize(commit.manifestPath()).toString().replace('\\', '/');
        CommitPointer pointer = new CommitPointer(POINTER_VERSION, batch.datasetId(), batch.idempotencyKey(),
                requestSha256, relative, commit.manifestSha256());
        Path temporary = commitRoot.resolve("." + batch.idempotencyKey() + "-" + UUID.randomUUID() + ".tmp");
        try {
            writeJsonNew(temporary, pointer);
            forceFile(temporary);
            directoryDurabilityBarrier.force(commitRoot);
            try {
                Files.move(temporary, pointerPath, StandardCopyOption.ATOMIC_MOVE);
                directoryDurabilityBarrier.force(commitRoot);
            } catch (AtomicMoveNotSupportedException error) {
                throw new ArchiveValidationException("ATOMIC_MOVE_UNSUPPORTED",
                        "commit pointer requires an atomic same-filesystem move", error);
            }
        } catch (FileAlreadyExistsException raced) {
            ArchiveCommit existing = fromPointer(pointerPath, batch, requestSha256, true);
            if (!existing.manifestSha256().equals(commit.manifestSha256())) {
                throw new ArchiveIdentityConflictException("concurrent commit pointer identifies different facts");
            }
        } catch (IOException error) {
            throw new ArchiveValidationException("COMMIT_POINTER_WRITE_FAILED",
                    "cannot publish archive commit pointer: " + error.getMessage(), error);
        } finally {
            tryDeleteFile(temporary);
        }
    }

    private VerifiedContent verifiedContent(Path requestedManifest) {
        Path manifestPath = regularArchiveFile(requestedManifest, "INVALID_MANIFEST");
        ArchiveManifest manifest = readJson(manifestPath, ArchiveManifest.class);
        Path dataPath = regularArchiveFile(manifestPath.getParent().resolve(manifest.dataFile()),
                "INVALID_PARQUET_OBJECT");
        if (size(dataPath) != manifest.byteSize()) {
            throw new ArchiveValidationException("OBJECT_SIZE_MISMATCH", "Parquet byte size differs from manifest");
        }
        if (!sha256(dataPath).equals(manifest.dataSha256())) {
            throw new ArchiveValidationException("OBJECT_CHECKSUM_MISMATCH", "Parquet SHA-256 differs from manifest");
        }
        if (!codec.schemaFingerprint(manifest.eventType()).equals(manifest.schemaFingerprintSha256())) {
            throw new ArchiveValidationException("SCHEMA_FINGERPRINT_MISMATCH",
                    "manifest schema fingerprint is not the supported immutable schema");
        }
        List<ArchivableMarketData> events = codec.read(dataPath, manifest.eventType());
        ArchiveBatch reconstructed = new ArchiveBatch(manifest.datasetId(), manifest.idempotencyKey(),
                manifest.sourceUri(), manifest.immutableSourceSnapshotId(), events);
        BatchFacts facts = analyze(events);
        if (events.size() != manifest.rowCount()
                || facts.type() != manifest.eventType()
                || facts.source() != manifest.source()
                || facts.exchange() != manifest.exchange()
                || facts.marketType() != manifest.marketType()
                || !facts.symbol().equals(manifest.symbol())
                || !facts.eventDate().toString().equals(manifest.eventDateUtc())
                || facts.startInclusive() != manifest.startInclusiveEpochMillis()
                || facts.endExclusive() != manifest.endExclusiveEpochMillis()) {
            throw new ArchiveValidationException("MANIFEST_FACT_MISMATCH",
                    "decoded Parquet facts differ from manifest identity, range or row count");
        }
        if (!requestSha256(reconstructed, manifest.eventType()).equals(manifest.requestSha256())) {
            throw new ArchiveValidationException("REQUEST_CHECKSUM_MISMATCH",
                    "decoded canonical facts differ from manifest request SHA-256");
        }
        ArchiveVerification verification = new ArchiveVerification(manifest, manifestPath, dataPath,
                sha256(manifestPath), events.size());
        return new VerifiedContent(verification, events);
    }

    private BatchFacts analyze(List<ArchivableMarketData> events) {
        ArchivableMarketData first = events.get(0);
        ArchiveEventType type = ArchiveEventType.from(first);
        PartitionIdentity identity = identity(first);
        LocalDate date = utcDate(first.metadata().exchangeTimestamp());
        long previousTimestamp = -1;
        long start = Long.MAX_VALUE;
        long endInclusive = Long.MIN_VALUE;
        Set<String> keys = new HashSet<>();
        for (ArchivableMarketData event : events) {
            if (ArchiveEventType.from(event) != type || !identity(event).equals(identity)
                    || !utcDate(event.metadata().exchangeTimestamp()).equals(date)) {
                throw new ArchiveValidationException("MIXED_PARTITION",
                        "one archive batch must contain exactly one type/source/instrument/UTC-day partition");
            }
            long timestamp = event.metadata().exchangeTimestamp();
            if (timestamp <= 0 || event.metadata().receivedTimestamp() <= 0) {
                throw new ArchiveValidationException("INVALID_EVENT_TIME", "event timestamps must be positive");
            }
            if (timestamp < previousTimestamp) {
                throw new ArchiveValidationException("NON_MONOTONIC_EVENT_TIME",
                        "events must be ordered by non-decreasing exchange timestamp");
            }
            previousTimestamp = timestamp;
            start = Math.min(start, timestamp);
            endInclusive = Math.max(endInclusive, timestamp);
            if (!keys.add(ParquetMarketEventCodec.eventKey(event))) {
                throw new ArchiveValidationException("DUPLICATE_EVENT_IDENTITY",
                        "batch contains a duplicate natural event identity");
            }
        }
        long endExclusive;
        try {
            endExclusive = Math.addExact(endInclusive, 1L);
        } catch (ArithmeticException overflow) {
            throw new ArchiveValidationException("EVENT_TIME_OVERFLOW", "event time cannot form a half-open range");
        }
        return new BatchFacts(type, identity.source(), identity.exchange(), identity.marketType(), identity.symbol(),
                date, start, endExclusive);
    }

    private PartitionIdentity identity(ArchivableMarketData event) {
        if (event instanceof RawMarketMessage raw) {
            return new PartitionIdentity(raw.metadata().source(), raw.metadata().source(), raw.marketType(),
                    safePartition(raw.symbolScope(), "symbolScope"));
        }
        MarketEvent market = (MarketEvent) event;
        Instrument instrument = market.instrument();
        if (market.metadata().source() != instrument.exchange()) {
            throw new ArchiveValidationException("SOURCE_EXCHANGE_MISMATCH",
                    "metadata source must equal canonical instrument exchange");
        }
        return new PartitionIdentity(market.metadata().source(), instrument.exchange(), instrument.marketType(),
                safePartition(instrument.symbol(), "symbol"));
    }

    private Path targetDirectory(ArchiveBatch batch, BatchFacts facts) {
        Path target = root.resolve("dataset=" + batch.datasetId())
                .resolve("event_type=" + facts.type().partitionValue())
                .resolve("source=" + facts.source().name().toLowerCase(Locale.ROOT))
                .resolve("exchange=" + facts.exchange().getCode())
                .resolve("market_type=" + facts.marketType().getCode())
                .resolve("symbol=" + facts.symbol())
                .resolve("event_date=" + facts.eventDate())
                .resolve("batch=" + batch.idempotencyKey()).normalize();
        requireWithinRoot(target);
        return target;
    }

    private String requestSha256(ArchiveBatch batch, ArchiveEventType type) {
        MessageDigest digest = digest();
        update(digest, batch.datasetId());
        update(digest, batch.idempotencyKey());
        update(digest, batch.sourceUri());
        update(digest, batch.immutableSourceSnapshotId());
        update(digest, type.name());
        update(digest, codec.schemaFingerprint(type));
        long row = 0;
        for (ArchivableMarketData event : batch.events()) update(digest, codec.canonicalBytes(type, row++, event));
        return HexFormat.of().formatHex(digest.digest());
    }

    private <T> T readJson(Path path, Class<T> type) {
        Path file = regularArchiveFile(path, "INVALID_JSON_ARTIFACT");
        try {
            return json.readValue(file.toFile(), type);
        } catch (IOException | RuntimeException error) {
            if (error instanceof ArchiveValidationException validation) throw validation;
            throw new ArchiveValidationException("INVALID_JSON_ARTIFACT",
                    "cannot read strict JSON artifact " + file.getFileName() + ": " + error.getMessage(), error);
        }
    }

    private void writeJsonNew(Path target, Object value) throws IOException {
        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            json.writerWithDefaultPrettyPrinter().writeValue(output, value);
        }
    }

    private Path regularArchiveFile(Path path, String code) {
        if (path == null) throw new ArchiveValidationException(code, "artifact path is required");
        Path normalized = path.toAbsolutePath().normalize();
        requireWithinRoot(normalized);
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArchiveValidationException(code, "artifact must be a regular non-symlink file: " + normalized);
        }
        return normalized;
    }

    private void createOwnedDirectory(Path directory) {
        requireWithinRoot(directory);
        try {
            Files.createDirectories(directory);
            Path cursor = root;
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new ArchiveValidationException("UNSAFE_ARCHIVE_ROOT", "archive root is not a real directory");
            }
            Path relative = root.relativize(directory.toAbsolutePath().normalize());
            for (Path component : relative) {
                cursor = cursor.resolve(component);
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ArchiveValidationException("UNSAFE_ARCHIVE_PATH",
                            "archive directory tree contains a symlink or non-directory");
                }
            }
        } catch (ArchiveValidationException error) {
            throw error;
        } catch (IOException error) {
            throw new ArchiveValidationException("ARCHIVE_DIRECTORY_FAILED",
                    "cannot create archive directory: " + error.getMessage(), error);
        }
    }

    private void requireWithinRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new ArchiveValidationException("PATH_ESCAPE", "archive path escapes configured root");
        }
    }

    private static String safePartition(String value, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SAFE_PARTITION.matcher(normalized).matches()) {
            throw new ArchiveValidationException("UNSAFE_PARTITION_VALUE",
                    field + " contains characters unsafe for an object-store partition");
        }
        return normalized;
    }

    private static LocalDate utcDate(long epochMillis) {
        try {
            return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (RuntimeException error) {
            throw new ArchiveValidationException("INVALID_EVENT_TIME", "event timestamp is outside UTC range", error);
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException error) {
            throw new ArchiveValidationException("ARTIFACT_SIZE_FAILED", "cannot read artifact size", error);
        }
    }

    private static String sha256(Path path) {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new ArchiveValidationException("ARTIFACT_HASH_FAILED", "cannot hash artifact", error);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        update(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void update(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBufferLength.bytes(bytes.length));
        digest.update(bytes);
    }

    private void deleteStaging(Path directory) {
        if (directory == null || !directory.toAbsolutePath().normalize().startsWith(stagingRoot)) return;
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            List<Path> children = new ArrayList<>(paths.toList());
            children.sort(Comparator.reverseOrder());
            for (Path child : children) Files.deleteIfExists(child);
        } catch (IOException ignored) {
            // Orphan staging is never committed or readable; a separate bounded sweeper may remove it later.
        }
    }

    private static void tryDeleteFile(Path file) {
        try {
            if (file != null) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // An unreferenced temporary pointer is not a committed archive.
        }
    }

    private record PartitionIdentity(Exchange source, Exchange exchange, MarketType marketType, String symbol) {}

    private record BatchFacts(ArchiveEventType type, Exchange source, Exchange exchange, MarketType marketType,
                              String symbol, LocalDate eventDate, long startInclusive, long endExclusive) {}

    private record CommitPointer(String pointerVersion, String datasetId, String idempotencyKey,
                                 String requestSha256, String manifestRelativePath, String manifestSha256) {}

    private record VerifiedContent(ArchiveVerification verification, List<ArchivableMarketData> events) {}

    private static final class ByteBufferLength {
        private ByteBufferLength() {}

        static byte[] bytes(int value) {
            return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16),
                    (byte) (value >>> 8), (byte) value};
        }
    }
}
