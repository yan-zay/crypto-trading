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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFsyncMarketIngestJournalTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsRawBeforeCanonicalWithStableLineageAndIdempotentReplay() {
        RecordingDirectoryDurabilityBarrier barrier = new RecordingDirectoryDurabilityBarrier();
        LocalFsyncMarketIngestJournal journal = new LocalFsyncMarketIngestJournal(
                temporaryDirectory, Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC), barrier);
        RawMarketMessage raw = raw(1_000, 1_100, "{\"trade\":\"1\"}");

        RawJournalReceipt firstRaw = journal.retainRaw(raw);
        RawJournalReceipt duplicateRaw = journal.retainRaw(raw);
        assertThat(firstRaw.alreadyPresent()).isFalse();
        assertThat(duplicateRaw.alreadyPresent()).isTrue();
        assertThat(firstRaw.rawMessageId()).hasSize(64)
                .isEqualTo(duplicateRaw.rawMessageId())
                .isEqualTo(firstRaw.message().metadata().rawMessageId());

        TradeEvent event = trade(1_000, 1_100, null, "trade-1", "100.00");
        CanonicalJournalReceipt firstEvent = journal.retainCanonical(firstRaw.rawMessageId(), 0, event);
        CanonicalJournalReceipt duplicateEvent = journal.retainCanonical(firstRaw.rawMessageId(), 0, event);
        assertThat(firstEvent.alreadyPresent()).isFalse();
        assertThat(duplicateEvent.alreadyPresent()).isTrue();
        assertThat(firstEvent.event().metadata().rawMessageId()).isEqualTo(firstRaw.rawMessageId());
        assertThat(firstEvent.eventId()).isEqualTo(
                LocalFsyncMarketIngestJournal.stableCanonicalEventId(firstRaw.rawMessageId(), 0));

        NormalizationFailureReceipt failure = journal.recordNormalizationFailure(
                firstRaw.rawMessageId(), "SECOND_ROW_INVALID", "one aggregate row was rejected");
        assertThat(journal.recordNormalizationFailure(firstRaw.rawMessageId(),
                "SECOND_ROW_INVALID", "one aggregate row was rejected").alreadyPresent()).isTrue();

        JournalReplay replay = journal.replay();
        assertThat(replay.rawMessages()).extracting(RawJournalReceipt::rawMessageId)
                .containsExactly(firstRaw.rawMessageId());
        assertThat(replay.canonicalEvents()).extracting(CanonicalJournalReceipt::eventId)
                .containsExactly(firstEvent.eventId());
        assertThat(replay.normalizationFailures()).extracting(NormalizationFailureReceipt::failureId)
                .containsExactly(failure.failureId());
        assertThat(journal.realtimeCaptureCapability()).isEqualTo(RealtimeCaptureCapability.NOT_WIRED);
        assertThat(barrier.calls()).contains(temporaryDirectory.resolve("raw").toAbsolutePath(),
                temporaryDirectory.resolve("canonical").toAbsolutePath(),
                temporaryDirectory.resolve("normalization-failures").toAbsolutePath());
    }

    @Test
    void rejectsDerivedFactsWithoutRawAndConflictingOrdinalReuse() {
        LocalFsyncMarketIngestJournal journal = journalWithRecordingBarrier();
        String absentRaw = "a".repeat(64);
        assertThatThrownBy(() -> journal.retainCanonical(absentRaw, 0,
                trade(1_000, 1_100, null, "trade-1", "100")))
                .isInstanceOf(ArchiveValidationException.class)
                .hasMessageContaining("durably retained");

        RawJournalReceipt raw = journal.retainRaw(raw(1_000, 1_100, "{\"trade\":\"1\"}"));
        journal.retainCanonical(raw.rawMessageId(), 0,
                trade(1_000, 1_100, null, "trade-1", "100"));
        assertThatThrownBy(() -> journal.retainCanonical(raw.rawMessageId(), 0,
                trade(1_000, 1_100, null, "trade-1", "101")))
                .isInstanceOf(ArchiveIdentityConflictException.class);
    }

    @Test
    void corruptDurableRecordStopsReplayInsteadOfBeingSkipped() throws Exception {
        LocalFsyncMarketIngestJournal journal = journalWithRecordingBarrier();
        RawJournalReceipt receipt = journal.retainRaw(raw(1_000, 1_100, "{\"trade\":\"1\"}"));
        Files.writeString(temporaryDirectory.resolve("raw").resolve(receipt.rawMessageId() + ".json"), "{");

        assertThatThrownBy(journal::replay)
                .isInstanceOf(ArchiveValidationException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    void refusesDurableReceiptWhenParentDirectoryBarrierIsNotVerified() {
        RecordingDirectoryDurabilityBarrier barrier = new RecordingDirectoryDurabilityBarrier();
        barrier.failNext();
        LocalFsyncMarketIngestJournal journal = new LocalFsyncMarketIngestJournal(
                temporaryDirectory, Clock.systemUTC(), barrier);
        RawMarketMessage raw = raw(1_000, 1_100, "{\"trade\":\"1\"}");

        assertThatThrownBy(() -> journal.retainRaw(raw))
                .isInstanceOfSatisfying(ArchiveValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("DIRECTORY_DURABILITY_NOT_VERIFIED"));

        // The CREATE_NEW bytes may exist, but no strict receipt was returned. A verified retry
        // must cross the barrier again before acknowledging the already-present record.
        RawJournalReceipt recovered = journal.retainRaw(raw);
        assertThat(recovered.alreadyPresent()).isTrue();
        assertThat(barrier.calls()).hasSize(2);
    }

    private LocalFsyncMarketIngestJournal journalWithRecordingBarrier() {
        return new LocalFsyncMarketIngestJournal(temporaryDirectory, Clock.systemUTC(),
                new RecordingDirectoryDurabilityBarrier());
    }

    private static RawMarketMessage raw(long exchangeTime, long receivedTime, String payload) {
        return RawMarketMessage.of(new EventMetadata(Exchange.BINANCE, exchangeTime, receivedTime, null),
                MarketType.PERPETUAL, "BTCUSDT", "aggTrade", "application/json", payload);
    }

    private static TradeEvent trade(long exchangeTime, long receivedTime, String rawId,
                                    String tradeId, String price) {
        return new TradeEvent(Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"),
                new EventMetadata(Exchange.BINANCE, exchangeTime, receivedTime, rawId), tradeId,
                new BigDecimal(price), new BigDecimal("0.01"), TradeSide.BUY);
    }
}
