package com.tj.crypto.marketdata.archive;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.marketdata.model.ArchivableMarketData;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.FundingRateEvent;
import com.tj.crypto.marketdata.model.IndexPriceEvent;
import com.tj.crypto.marketdata.model.L2OrderBookEvent;
import com.tj.crypto.marketdata.model.MarkPriceEvent;
import com.tj.crypto.marketdata.model.OpenInterestEvent;
import com.tj.crypto.marketdata.model.OrderBookLevel;
import com.tj.crypto.marketdata.model.RawMarketMessage;
import com.tj.crypto.marketdata.model.TradeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalParquetMarketDataArchiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAndReadsRealVersionedParquetSchemasForEveryRequiredMarketFact() throws Exception {
        RecordingDirectoryDurabilityBarrier barrier = new RecordingDirectoryDurabilityBarrier();
        LocalParquetMarketDataArchive archive = new LocalParquetMarketDataArchive(
                temporaryDirectory, Clock.systemUTC(), barrier);
        List<ArchivableMarketData> facts = List.of(
                trade("trade-1", "100"),
                new L2OrderBookEvent(instrument(), metadata(), "book-1", 1, 2, 0L, 77L, true,
                        List.of(new OrderBookLevel(new BigDecimal("99"), new BigDecimal("1"))),
                        List.of(new OrderBookLevel(new BigDecimal("101"), new BigDecimal("2")))),
                new MarkPriceEvent(instrument(), metadata(), new BigDecimal("100.5"), null),
                new IndexPriceEvent(instrument(), metadata(), new BigDecimal("100.4")),
                new FundingRateEvent(instrument(), metadata(), new BigDecimal("0.0001"), null, 2_000),
                new OpenInterestEvent(instrument(), metadata(), new BigDecimal("10"), new BigDecimal("1000")),
                RawMarketMessage.of(metadata(), MarketType.PERPETUAL, "BTCUSDT", "raw", "application/json",
                        "{\"raw\":true}")
        );

        for (int index = 0; index < facts.size(); index++) {
            String id = "schema-" + index;
            ArchiveCommit commit = archive.archive(new ArchiveBatch("btc-benchmark", id,
                    "https://data.example.test/snapshot/" + id, "snapshot-" + id, List.of(facts.get(index))));
            byte[] parquet = Files.readAllBytes(commit.dataPath());
            assertThat(new String(parquet, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
            assertThat(new String(parquet, parquet.length - 4, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
            assertThat(archive.verify(commit.manifestPath()).verifiedRows()).isEqualTo(1);
            assertThat(archive.read(commit.manifestPath())).hasSize(1)
                    .first().isInstanceOf(facts.get(index).getClass());
            assertThat(archive.archive(new ArchiveBatch("btc-benchmark", id,
                    "https://data.example.test/snapshot/" + id, "snapshot-" + id,
                    List.of(facts.get(index)))).alreadyPresent()).isTrue();
        }
        assertThat(barrier.calls()).contains(temporaryDirectory.resolve("_commits").toAbsolutePath());
    }

    @Test
    void neverOverwritesIdempotencyKeyAndDetectsObjectTampering() throws Exception {
        LocalParquetMarketDataArchive archive = archiveWithRecordingBarrier(
                new RecordingDirectoryDurabilityBarrier());
        ArchiveBatch batch = new ArchiveBatch("btc-benchmark", "immutable-batch",
                "https://data.example.test/snapshot/immutable", "snapshot-immutable", List.of(trade("t1", "100")));
        ArchiveCommit commit = archive.archive(batch);

        assertThatThrownBy(() -> archive.archive(new ArchiveBatch("btc-benchmark", "immutable-batch",
                "https://data.example.test/snapshot/immutable", "snapshot-immutable", List.of(trade("t1", "101")))))
                .isInstanceOf(ArchiveIdentityConflictException.class);

        byte[] bytes = Files.readAllBytes(commit.dataPath());
        bytes[8] ^= 0x01;
        Files.write(commit.dataPath(), bytes);
        assertThatThrownBy(() -> archive.verify(commit.manifestPath()))
                .isInstanceOf(ArchiveValidationException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void refusesCommitReceiptWhenDirectoryBarrierCannotVerifyAtomicPublication() {
        RecordingDirectoryDurabilityBarrier barrier = new RecordingDirectoryDurabilityBarrier();
        barrier.failNext();
        LocalParquetMarketDataArchive archive = archiveWithRecordingBarrier(barrier);

        assertThatThrownBy(() -> archive.archive(new ArchiveBatch("btc-benchmark", "barrier-failure",
                "https://data.example.test/snapshot/barrier", "snapshot-barrier",
                List.of(trade("t1", "100")))))
                .isInstanceOfSatisfying(ArchiveValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("DIRECTORY_DURABILITY_NOT_VERIFIED"));
    }

    private LocalParquetMarketDataArchive archiveWithRecordingBarrier(
            RecordingDirectoryDurabilityBarrier barrier) {
        return new LocalParquetMarketDataArchive(temporaryDirectory, Clock.systemUTC(), barrier);
    }

    private static TradeEvent trade(String id, String price) {
        return new TradeEvent(instrument(), metadata(), id, new BigDecimal(price),
                new BigDecimal("0.01"), TradeSide.BUY);
    }

    private static Instrument instrument() {
        return Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private static EventMetadata metadata() {
        return new EventMetadata(Exchange.BINANCE, 1_000, 1_100, "a".repeat(64));
    }
}
