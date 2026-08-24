package com.tj.crypto.marketdata.archive;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.marketdata.model.ArchivableMarketData;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.model.RawMarketMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 将一个经过完整校验的 raw-first journal 快照压实为按类型/venue/market/symbol/UTC day 分区的 Parquet。
 * normalization failure 保留在 WAL 并进入快照校验和及计数，不会伪造成 market event 行。
 */
public final class JournalParquetCompactor {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final LiveMarketIngestJournal journal;
    private final ImmutableMarketDataArchive archive;
    private final String journalName;

    public JournalParquetCompactor(LiveMarketIngestJournal journal, ImmutableMarketDataArchive archive,
                                   String journalName) {
        if (journal == null || archive == null) throw new IllegalArgumentException("journal and archive are required");
        if (journalName == null || !SAFE_ID.matcher(journalName.trim()).matches()) {
            throw new IllegalArgumentException("journalName must be a safe 1-64 character identifier");
        }
        this.journal = journal;
        this.archive = archive;
        this.journalName = journalName.trim();
    }

    public JournalCompactionReport compact(String datasetId) {
        if (datasetId == null || !SAFE_ID.matcher(datasetId.trim()).matches()) {
            throw new IllegalArgumentException("datasetId must be a safe 1-64 character identifier");
        }
        JournalReplay replay = journal.replay();
        String snapshotSha = snapshotSha256(replay);
        Map<PartitionKey, List<ArchivableMarketData>> partitions = new TreeMap<>();
        for (RawJournalReceipt receipt : replay.rawMessages()) {
            add(partitions, receipt.message());
        }
        for (CanonicalJournalReceipt receipt : replay.canonicalEvents()) {
            add(partitions, receipt.event());
        }

        List<ArchiveCommit> commits = new ArrayList<>();
        for (Map.Entry<PartitionKey, List<ArchivableMarketData>> entry : partitions.entrySet()) {
            List<ArchivableMarketData> events = entry.getValue().stream()
                    .sorted(Comparator.comparingLong((ArchivableMarketData event) ->
                                    event.metadata().exchangeTimestamp())
                            .thenComparing(ParquetMarketEventCodec::eventKey))
                    .toList();
            String partitionSnapshot = partitionSnapshotSha256(entry.getKey(), events);
            String idempotencyKey = "journal-" + partitionSnapshot.substring(0, 48);
            String sourceUri = "urn:crypto-trading:market-journal:" + journalName + ":" + partitionSnapshot;
            ArchiveBatch batch = new ArchiveBatch(datasetId.trim(), idempotencyKey, sourceUri,
                    "journal-" + partitionSnapshot, events);
            commits.add(archive.archive(batch));
        }
        return new JournalCompactionReport(snapshotSha, journal.realtimeCaptureCapability(),
                replay.rawMessages().size(), replay.canonicalEvents().size(),
                replay.normalizationFailures().size(), commits);
    }

    private static void add(Map<PartitionKey, List<ArchivableMarketData>> partitions,
                            ArchivableMarketData event) {
        partitions.computeIfAbsent(PartitionKey.from(event), ignored -> new ArrayList<>()).add(event);
    }

    private static String snapshotSha256(JournalReplay replay) {
        MessageDigest digest = digest();
        update(digest, "journal-snapshot-v1");
        replay.rawMessages().forEach(receipt -> update(digest, "raw:" + receipt.rawMessageId()));
        replay.canonicalEvents().forEach(receipt -> update(digest, "canonical:" + receipt.eventId()));
        replay.normalizationFailures().forEach(receipt -> update(digest, "failure:" + receipt.failureId()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String partitionSnapshotSha256(PartitionKey key, List<ArchivableMarketData> events) {
        MessageDigest digest = digest();
        update(digest, "journal-partition-v1");
        update(digest, key.toString());
        events.forEach(event -> update(digest, ParquetMarketEventCodec.eventKey(event)));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record PartitionKey(ArchiveEventType eventType, Exchange source, Exchange exchange,
                                MarketType marketType, String symbol, String eventDateUtc)
            implements Comparable<PartitionKey> {
        static PartitionKey from(ArchivableMarketData event) {
            String date = Instant.ofEpochMilli(event.metadata().exchangeTimestamp())
                    .atZone(ZoneOffset.UTC).toLocalDate().toString();
            if (event instanceof RawMarketMessage raw) {
                return new PartitionKey(ArchiveEventType.RAW, raw.metadata().source(), raw.metadata().source(),
                        raw.marketType(), raw.symbolScope(), date);
            }
            MarketEvent market = (MarketEvent) event;
            Instrument instrument = market.instrument();
            return new PartitionKey(ArchiveEventType.from(event), market.metadata().source(),
                    instrument.exchange(), instrument.marketType(), instrument.symbol(), date);
        }

        @Override
        public int compareTo(PartitionKey other) {
            int result = eventType.compareTo(other.eventType);
            if (result != 0) return result;
            result = source.compareTo(other.source);
            if (result != 0) return result;
            result = exchange.compareTo(other.exchange);
            if (result != 0) return result;
            result = marketType.compareTo(other.marketType);
            if (result != 0) return result;
            result = symbol.compareTo(other.symbol);
            return result != 0 ? result : eventDateUtc.compareTo(other.eventDateUtc);
        }
    }
}
