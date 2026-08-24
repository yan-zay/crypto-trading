package com.tj.crypto.marketdata.archive;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.RawMarketMessage;
import com.tj.crypto.marketdata.model.TradeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class JournalParquetCompactorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void compactsRawAndCanonicalPartitionsWithoutClaimingRealtimeConnectorWiring() {
        LocalFsyncMarketIngestJournal journal = new LocalFsyncMarketIngestJournal(
                temporaryDirectory.resolve("journal"), Clock.systemUTC(),
                new RecordingDirectoryDurabilityBarrier());
        RawMarketMessage message = RawMarketMessage.of(
                new EventMetadata(Exchange.BINANCE, 1_000, 1_100, null), MarketType.PERPETUAL,
                "BTCUSDT", "aggTrade", "application/json", "{\"trade\":1}");
        RawJournalReceipt raw = journal.retainRaw(message);
        journal.retainCanonical(raw.rawMessageId(), 0, new TradeEvent(
                Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"),
                new EventMetadata(Exchange.BINANCE, 1_000, 1_100, null), "trade-1",
                new BigDecimal("100"), new BigDecimal("0.1"), TradeSide.BUY));
        journal.recordNormalizationFailure(raw.rawMessageId(), "OPTIONAL_ROW_REJECTED", "fixture failure");

        LocalParquetMarketDataArchive archive = new LocalParquetMarketDataArchive(
                temporaryDirectory.resolve("lake"), Clock.systemUTC(),
                new RecordingDirectoryDurabilityBarrier());
        JournalParquetCompactor compactor = new JournalParquetCompactor(journal, archive, "public-ws-spool");
        JournalCompactionReport first = compactor.compact("btc-benchmark");
        JournalCompactionReport second = compactor.compact("btc-benchmark");

        assertThat(first.realtimeCaptureCapability()).isEqualTo(RealtimeCaptureCapability.NOT_WIRED);
        assertThat(first.rawMessageCount()).isEqualTo(1);
        assertThat(first.canonicalEventCount()).isEqualTo(1);
        assertThat(first.normalizationFailureCount()).isEqualTo(1);
        assertThat(first.commits()).hasSize(2);
        assertThat(first.commits()).allSatisfy(commit -> {
            assertThat(commit.manifest().sourceUri()).startsWith("urn:crypto-trading:market-journal:");
            assertThat(archive.verify(commit.manifestPath()).verifiedRows()).isEqualTo(1);
        });
        assertThat(second.journalSnapshotSha256()).isEqualTo(first.journalSnapshotSha256());
        assertThat(second.commits()).allSatisfy(commit -> assertThat(commit.alreadyPresent()).isTrue());
    }
}
