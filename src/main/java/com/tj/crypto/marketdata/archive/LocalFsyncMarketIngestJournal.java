package com.tj.crypto.marketdata.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.FundingRateEvent;
import com.tj.crypto.marketdata.model.IndexPriceEvent;
import com.tj.crypto.marketdata.model.L2OrderBookEvent;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarkPriceEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.model.OpenInterestEvent;
import com.tj.crypto.marketdata.model.RawMarketMessage;
import com.tj.crypto.marketdata.model.TradeEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 本地、append-only 的 raw-first 行情 WAL。
 *
 * <p>每条事实写入独立的 CREATE_NEW 文件并在返回 receipt 前执行 {@link FileChannel#force(boolean)}。
 * 崩溃留下的半条记录不会被覆盖或忽略：后续读取会 fail-closed，要求人工保留证据并修复。
 * 普通构造器保持 {@code NOT_WIRED}；只有 Spring 生产装配在所有受支持 WebSocket 连接器均经统一
 * coordinator 写入时才使用 {@link #wired(Path)}。本地冷层存在本身不能冒充真实连续采集已验收。</p>
 */
public final class LocalFsyncMarketIngestJournal implements LiveMarketIngestJournal {
    private static final String JOURNAL_VERSION = "1";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_RAW_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FAILURE_DETAIL_CHARS = 4096;

    private final Path root;
    private final Path rawRoot;
    private final Path canonicalRoot;
    private final Path failureRoot;
    private final Path lockRoot;
    private final Clock clock;
    private final DirectoryDurabilityBarrier directoryDurabilityBarrier;
    private final RealtimeCaptureCapability realtimeCaptureCapability;
    private final ObjectMapper json = ArchiveJson.mapper();

    public LocalFsyncMarketIngestJournal(Path root) {
        this(root, Clock.systemUTC(), DirectoryDurabilityBarrier.strictPlatform(),
                RealtimeCaptureCapability.NOT_WIRED);
    }

    /** Spring production assembly uses this only when all supported live connectors use the coordinator. */
    public static LocalFsyncMarketIngestJournal wired(Path root) {
        return new LocalFsyncMarketIngestJournal(root, Clock.systemUTC(),
                DirectoryDurabilityBarrier.strictPlatform(), RealtimeCaptureCapability.WIRED);
    }

    LocalFsyncMarketIngestJournal(Path root, Clock clock) {
        this(root, clock, DirectoryDurabilityBarrier.strictPlatform(),
                RealtimeCaptureCapability.NOT_WIRED);
    }

    LocalFsyncMarketIngestJournal(Path root, Clock clock,
                                  DirectoryDurabilityBarrier directoryDurabilityBarrier) {
        this(root, clock, directoryDurabilityBarrier, RealtimeCaptureCapability.NOT_WIRED);
    }

    LocalFsyncMarketIngestJournal(Path root, Clock clock,
                                  DirectoryDurabilityBarrier directoryDurabilityBarrier,
                                  RealtimeCaptureCapability realtimeCaptureCapability) {
        if (root == null || clock == null || directoryDurabilityBarrier == null) {
            throw new IllegalArgumentException("root, clock and directory durability barrier are required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.rawRoot = this.root.resolve("raw");
        this.canonicalRoot = this.root.resolve("canonical");
        this.failureRoot = this.root.resolve("normalization-failures");
        this.lockRoot = this.root.resolve("_locks");
        this.clock = clock;
        this.directoryDurabilityBarrier = directoryDurabilityBarrier;
        this.realtimeCaptureCapability = java.util.Objects.requireNonNull(
                realtimeCaptureCapability, "realtimeCaptureCapability");
        createOwnedDirectory(this.root);
        createOwnedDirectory(rawRoot);
        createOwnedDirectory(canonicalRoot);
        createOwnedDirectory(failureRoot);
        createOwnedDirectory(lockRoot);
    }

    @Override
    public RawJournalReceipt retainRaw(RawMarketMessage rawMessage) {
        if (rawMessage == null) throw failure("MISSING_RAW_MESSAGE", "raw market message is required");
        if (rawMessage.payload().getBytes(StandardCharsets.UTF_8).length > MAX_RAW_PAYLOAD_BYTES) {
            throw failure("RAW_MESSAGE_TOO_LARGE", "raw market message exceeds the durable journal limit");
        }
        String rawMessageId = stableRawMessageId(rawMessage);
        String suppliedId = rawMessage.metadata().rawMessageId();
        if (suppliedId != null && !suppliedId.isBlank() && !rawMessageId.equals(suppliedId.trim())) {
            throw failure("RAW_MESSAGE_ID_MISMATCH",
                    "metadata rawMessageId does not match the stable raw fact identity");
        }
        EventMetadata rawMetadata = new EventMetadata(rawMessage.metadata().source(),
                rawMessage.metadata().exchangeTimestamp(), rawMessage.metadata().receivedTimestamp(), rawMessageId);
        RawMarketMessage tracedRaw = new RawMarketMessage(rawMetadata, rawMessage.marketType(),
                rawMessage.symbolScope(), rawMessage.channel(), rawMessage.contentType(), rawMessage.payload(),
                rawMessage.payloadSha256());
        return withRecordLock("raw-" + rawMessageId, () -> {
            Path target = rawRoot.resolve(rawMessageId + ".json");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                directoryDurabilityBarrier.force(target.getParent());
                return readRaw(target, true);
            }
            long durableAt = positiveClockMillis();
            RawEnvelope envelope = new RawEnvelope(JOURNAL_VERSION, rawMessageId,
                    rawPayloadDigest(tracedRaw), durableAt, tracedRaw);
            writeDurablyNew(target, envelope);
            return readRaw(target, false);
        });
    }

    @Override
    public CanonicalJournalReceipt retainCanonical(String rawMessageId, int rawOrdinal, MarketEvent event) {
        String normalizedRawId = requireSha(rawMessageId, "rawMessageId");
        if (rawOrdinal < 0) throw failure("INVALID_RAW_ORDINAL", "rawOrdinal must not be negative");
        if (event == null) throw failure("MISSING_CANONICAL_EVENT", "canonical event is required");
        ArchiveEventType eventType = ArchiveEventType.from(event);
        RawJournalReceipt raw = readRaw(rawPath(normalizedRawId), false);
        MarketEvent traced = withRawMessageId(event, normalizedRawId);
        if (traced.metadata().source() != raw.message().metadata().source()) {
            throw failure("RAW_SOURCE_MISMATCH", "canonical event source differs from its raw message source");
        }
        String eventId = stableCanonicalEventId(normalizedRawId, rawOrdinal);
        return withRecordLock("canonical-" + eventId, () -> {
            Path target = canonicalRoot.resolve(eventId + ".json");
            JsonNode eventNode = durableEventNode(traced);
            String eventSha = canonicalEventDigest(eventType, eventNode);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                directoryDurabilityBarrier.force(target.getParent());
                CanonicalJournalReceipt existing = readCanonical(target, true);
                if (!canonicalEventDigest(eventType, existing.event()).equals(eventSha)) {
                    throw new ArchiveIdentityConflictException(
                            "rawMessageId/rawOrdinal already identifies a different canonical event");
                }
                return existing;
            }
            long durableAt = positiveClockMillis();
            CanonicalEnvelope envelope = new CanonicalEnvelope(JOURNAL_VERSION, eventId,
                    normalizedRawId, rawOrdinal, eventType, eventSha, durableAt, eventNode);
            writeDurablyNew(target, envelope);
            return readCanonical(target, false);
        });
    }

    @Override
    public NormalizationFailureReceipt recordNormalizationFailure(String rawMessageId, String code, String detail) {
        String normalizedRawId = requireSha(rawMessageId, "rawMessageId");
        readRaw(rawPath(normalizedRawId), false);
        String normalizedCode = required(code, "code", 100);
        String normalizedDetail = required(detail, "detail", MAX_FAILURE_DETAIL_CHARS);
        String failureId = sha256("normalization-failure\u0000" + normalizedRawId + "\u0000"
                + normalizedCode + "\u0000" + normalizedDetail);
        return withRecordLock("failure-" + failureId, () -> {
            Path target = failureRoot.resolve(failureId + ".json");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                directoryDurabilityBarrier.force(target.getParent());
                return readFailure(target, true);
            }
            long durableAt = positiveClockMillis();
            FailureEnvelope envelope = new FailureEnvelope(JOURNAL_VERSION, failureId, normalizedRawId,
                    normalizedCode, normalizedDetail, durableAt);
            writeDurablyNew(target, envelope);
            return readFailure(target, false);
        });
    }

    @Override
    public JournalReplay replay() {
        List<RawJournalReceipt> rawMessages = listJson(rawRoot).stream()
                .map(path -> readRaw(path, true))
                .sorted(Comparator.comparingLong((RawJournalReceipt receipt) ->
                                receipt.message().metadata().exchangeTimestamp())
                        .thenComparing(RawJournalReceipt::rawMessageId))
                .toList();
        Map<String, Integer> rawOrder = new HashMap<>();
        for (int index = 0; index < rawMessages.size(); index++) {
            rawOrder.put(rawMessages.get(index).rawMessageId(), index);
        }
        List<CanonicalJournalReceipt> canonical = listJson(canonicalRoot).stream()
                .map(path -> readCanonical(path, true))
                .sorted(Comparator.comparingInt((CanonicalJournalReceipt receipt) ->
                                rawOrder.getOrDefault(receipt.rawMessageId(), Integer.MAX_VALUE))
                        .thenComparingInt(CanonicalJournalReceipt::rawOrdinal)
                        .thenComparing(CanonicalJournalReceipt::eventId))
                .toList();
        List<NormalizationFailureReceipt> failures = listJson(failureRoot).stream()
                .map(path -> readFailure(path, true))
                .sorted(Comparator.comparingInt((NormalizationFailureReceipt receipt) ->
                                rawOrder.getOrDefault(receipt.rawMessageId(), Integer.MAX_VALUE))
                        .thenComparing(NormalizationFailureReceipt::failureId))
                .toList();
        return new JournalReplay(rawMessages, canonical, failures);
    }

    @Override
    public RealtimeCaptureCapability realtimeCaptureCapability() {
        return realtimeCaptureCapability;
    }

    public static String stableRawMessageId(RawMarketMessage raw) {
        if (raw == null) throw new IllegalArgumentException("raw message is required");
        return sha256("raw-v1\u0000" + raw.metadata().source().name() + "\u0000"
                + raw.marketType().name() + "\u0000" + raw.symbolScope() + "\u0000"
                + raw.channel() + "\u0000" + raw.contentType() + "\u0000"
                + raw.metadata().exchangeTimestamp() + "\u0000" + raw.payloadSha256());
    }

    public static String stableCanonicalEventId(String rawMessageId, int rawOrdinal) {
        String normalized = requireSha(rawMessageId, "rawMessageId");
        if (rawOrdinal < 0) throw new IllegalArgumentException("rawOrdinal must not be negative");
        return sha256("canonical-v1\u0000" + normalized + "\u0000" + rawOrdinal);
    }

    private RawJournalReceipt readRaw(Path path, boolean alreadyPresent) {
        RawEnvelope envelope = readStrict(path, RawEnvelope.class);
        if (!JOURNAL_VERSION.equals(envelope.journalVersion())) {
            throw failure("UNSUPPORTED_JOURNAL_VERSION", "unsupported raw journal version");
        }
        String expectedId = stableRawMessageId(envelope.message());
        if (!expectedId.equals(envelope.rawMessageId())
                || !rawPayloadDigest(envelope.message()).equals(envelope.payloadDigestSha256())) {
            throw failure("RAW_JOURNAL_INTEGRITY_FAILURE", "raw journal identity or payload digest is invalid");
        }
        return new RawJournalReceipt(envelope.rawMessageId(), envelope.message(),
                envelope.durableAtEpochMillis(), alreadyPresent);
    }

    private CanonicalJournalReceipt readCanonical(Path path, boolean alreadyPresent) {
        CanonicalEnvelope envelope = readStrict(path, CanonicalEnvelope.class);
        if (!JOURNAL_VERSION.equals(envelope.journalVersion())) {
            throw failure("UNSUPPORTED_JOURNAL_VERSION", "unsupported canonical journal version");
        }
        requireSha(envelope.rawMessageId(), "rawMessageId");
        if (!stableCanonicalEventId(envelope.rawMessageId(), envelope.rawOrdinal()).equals(envelope.eventId())) {
            throw failure("CANONICAL_JOURNAL_INTEGRITY_FAILURE", "canonical journal identity is invalid");
        }
        MarketEvent event;
        try {
            event = (MarketEvent) json.treeToValue(envelope.event(), envelope.eventType().eventClass());
        } catch (IOException | RuntimeException error) {
            throw failure("CANONICAL_JOURNAL_DECODE_FAILED", "cannot decode canonical event", error);
        }
        if (!envelope.eventType().equals(ArchiveEventType.from(event))
                || !envelope.rawMessageId().equals(event.metadata().rawMessageId())
                || !envelope.eventDigestSha256().equals(
                        canonicalEventDigest(envelope.eventType(), envelope.event()))) {
            throw failure("CANONICAL_JOURNAL_INTEGRITY_FAILURE",
                    "canonical event type, lineage or payload digest is invalid");
        }
        RawJournalReceipt raw = readRaw(rawPath(envelope.rawMessageId()), true);
        if (event.metadata().source() != raw.message().metadata().source()) {
            throw failure("RAW_SOURCE_MISMATCH", "canonical event source differs from its raw message source");
        }
        return new CanonicalJournalReceipt(envelope.eventId(), envelope.rawMessageId(), envelope.rawOrdinal(),
                event, envelope.durableAtEpochMillis(), alreadyPresent);
    }

    private NormalizationFailureReceipt readFailure(Path path, boolean alreadyPresent) {
        FailureEnvelope envelope = readStrict(path, FailureEnvelope.class);
        if (!JOURNAL_VERSION.equals(envelope.journalVersion())) {
            throw failure("UNSUPPORTED_JOURNAL_VERSION", "unsupported normalization-failure journal version");
        }
        requireSha(envelope.rawMessageId(), "rawMessageId");
        String expectedId = sha256("normalization-failure\u0000" + envelope.rawMessageId() + "\u0000"
                + envelope.code() + "\u0000" + envelope.detail());
        if (!expectedId.equals(envelope.failureId())) {
            throw failure("FAILURE_JOURNAL_INTEGRITY_FAILURE", "normalization failure identity is invalid");
        }
        readRaw(rawPath(envelope.rawMessageId()), true);
        return new NormalizationFailureReceipt(envelope.failureId(), envelope.rawMessageId(), envelope.code(),
                envelope.detail(), envelope.durableAtEpochMillis(), alreadyPresent);
    }

    private MarketEvent withRawMessageId(MarketEvent event, String rawMessageId) {
        String current = event.metadata().rawMessageId();
        if (current != null && !current.isBlank() && !rawMessageId.equals(current.trim())) {
            throw failure("CANONICAL_LINEAGE_MISMATCH",
                    "canonical event metadata references a different raw message");
        }
        if (rawMessageId.equals(current)) return event;
        EventMetadata metadata = new EventMetadata(event.metadata().source(),
                event.metadata().exchangeTimestamp(), event.metadata().receivedTimestamp(), rawMessageId);
        if (event instanceof TradeEvent value) {
            return new TradeEvent(value.instrument(), metadata, value.tradeId(), value.price(), value.quantity(),
                    value.quoteQuantity(), value.aggressorSide());
        }
        if (event instanceof BarEvent value) {
            return new BarEvent(value.instrument(), metadata, value.timeframe(), value.open(),
                    value.high(), value.low(), value.close(), value.volume(), value.quoteVolume(),
                    value.closed());
        }
        if (event instanceof LiquidationEvent value) {
            return new LiquidationEvent(value.instrument(), metadata, value.side(), value.price(),
                    value.quantity(), value.quantityUsd(), value.exchangeName());
        }
        if (event instanceof L2OrderBookEvent value) {
            return new L2OrderBookEvent(value.instrument(), metadata, value.bookEpoch(), value.firstUpdateId(),
                    value.lastUpdateId(), value.previousUpdateId(), value.checksum(), value.snapshot(),
                    value.bids(), value.asks());
        }
        if (event instanceof MarkPriceEvent value) {
            return new MarkPriceEvent(value.instrument(), metadata, value.markPrice(),
                    value.estimatedSettlementPrice());
        }
        if (event instanceof IndexPriceEvent value) {
            return new IndexPriceEvent(value.instrument(), metadata, value.indexPrice());
        }
        if (event instanceof FundingRateEvent value) {
            return new FundingRateEvent(value.instrument(), metadata, value.fundingRate(), value.predictedRate(),
                    value.nextFundingTime());
        }
        if (event instanceof OpenInterestEvent value) {
            return new OpenInterestEvent(value.instrument(), metadata, value.openInterest(),
                    value.openInterestUsd());
        }
        throw failure("UNSUPPORTED_EVENT_TYPE", "event cannot be retained by the canonical market journal");
    }

    private String rawPayloadDigest(RawMarketMessage message) {
        return objectDigest(message);
    }

    private String canonicalEventDigest(ArchiveEventType eventType, MarketEvent event) {
        return canonicalEventDigest(eventType, durableEventNode(event));
    }

    private String canonicalEventDigest(ArchiveEventType eventType, JsonNode event) {
        return sha256(eventType.name() + "\u0000" + objectDigest(event));
    }

    /**
     * JSON tree parsing normalizes numeric node representation (for example 1.00 to 1.0).
     * Hash the exact durable representation, not the pre-serialization BigDecimal scale.
     */
    private JsonNode durableEventNode(MarketEvent event) {
        try {
            return json.readTree(json.writeValueAsBytes(event));
        } catch (IOException error) {
            throw failure("JOURNAL_ENCODING_FAILED", "cannot canonicalize durable journal event", error);
        }
    }

    private String objectDigest(Object value) {
        try {
            return HexFormat.of().formatHex(digest().digest(json.writeValueAsBytes(value)));
        } catch (IOException error) {
            throw failure("JOURNAL_ENCODING_FAILED", "cannot encode durable journal fact", error);
        }
    }

    private Path rawPath(String rawMessageId) {
        Path path = rawRoot.resolve(requireSha(rawMessageId, "rawMessageId") + ".json");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw failure("RAW_MESSAGE_NOT_RETAINED", "raw message must be durably retained before derived facts");
        }
        return path;
    }

    private <T> T withRecordLock(String name, JournalOperation<T> operation) {
        Path lock = lockRoot.resolve(name + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            return operation.run();
        } catch (ArchiveValidationException error) {
            throw error;
        } catch (IOException error) {
            throw failure("JOURNAL_LOCK_FAILED", "cannot acquire durable journal record lock", error);
        }
    }

    private void writeDurablyNew(Path target, Object value) {
        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(value);
        } catch (IOException error) {
            throw failure("JOURNAL_ENCODING_FAILED", "cannot encode durable journal record", error);
        }
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } catch (FileAlreadyExistsException raced) {
            // The per-record lock prevents local races. A shared filesystem may not honor locks; caller verifies bytes.
        } catch (IOException error) {
            throw failure("JOURNAL_FSYNC_FAILED",
                    "raw/canonical fact was not acknowledged because durable fsync failed", error);
        }
        directoryDurabilityBarrier.force(target.getParent());
    }

    private <T> T readStrict(Path path, Class<T> type) {
        Path file = regularJournalFile(path);
        try {
            return json.readValue(file.toFile(), type);
        } catch (IOException | RuntimeException error) {
            if (error instanceof ArchiveValidationException validation) throw validation;
            throw failure("JOURNAL_RECORD_CORRUPT",
                    "journal record is incomplete or corrupt; replay stopped fail-closed: " + file.getFileName(),
                    error);
        }
    }

    private List<Path> listJson(Path directory) {
        try (var files = Files.list(directory)) {
            List<Path> result = new ArrayList<>();
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(result::add);
            result.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return result;
        } catch (IOException error) {
            throw failure("JOURNAL_REPLAY_FAILED", "cannot enumerate durable journal", error);
        }
    }

    private Path regularJournalFile(Path requested) {
        if (requested == null) throw failure("INVALID_JOURNAL_PATH", "journal path is required");
        Path normalized = requested.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("INVALID_JOURNAL_PATH", "journal record must be a regular non-symlink file");
        }
        return normalized;
    }

    private void createOwnedDirectory(Path directory) {
        if (!directory.toAbsolutePath().normalize().startsWith(root)) {
            throw failure("JOURNAL_PATH_ESCAPE", "journal directory escapes configured root");
        }
        try {
            Files.createDirectories(directory);
            Path cursor = root;
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("UNSAFE_JOURNAL_ROOT", "journal root is not a real directory");
            }
            for (Path component : root.relativize(directory.toAbsolutePath().normalize())) {
                cursor = cursor.resolve(component);
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("UNSAFE_JOURNAL_PATH", "journal directory contains a symlink");
                }
            }
        } catch (ArchiveValidationException error) {
            throw error;
        } catch (IOException error) {
            throw failure("JOURNAL_DIRECTORY_FAILED", "cannot create journal directory", error);
        }
    }

    private long positiveClockMillis() {
        long now = clock.millis();
        if (now <= 0) throw failure("INVALID_DURABILITY_TIME", "journal durability clock must be positive");
        return now;
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw failure("INVALID_" + field.toUpperCase(), field + " is required");
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw failure("INVALID_" + field.toUpperCase(), field + " exceeds maximum length");
        }
        return normalized;
    }

    private static String requireSha(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!SHA_256.matcher(normalized).matches()) {
            throw failure("INVALID_" + field.toUpperCase(), field + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ArchiveValidationException failure(String code, String message) {
        return new ArchiveValidationException(code, message);
    }

    private static ArchiveValidationException failure(String code, String message, Throwable cause) {
        return new ArchiveValidationException(code, message, cause);
    }

    @FunctionalInterface
    private interface JournalOperation<T> {
        T run();
    }

    private record RawEnvelope(String journalVersion, String rawMessageId, String payloadDigestSha256,
                               long durableAtEpochMillis, RawMarketMessage message) {}

    private record CanonicalEnvelope(String journalVersion, String eventId, String rawMessageId,
                                     int rawOrdinal, ArchiveEventType eventType, String eventDigestSha256,
                                     long durableAtEpochMillis, JsonNode event) {}

    private record FailureEnvelope(String journalVersion, String failureId, String rawMessageId,
                                   String code, String detail, long durableAtEpochMillis) {}
}
