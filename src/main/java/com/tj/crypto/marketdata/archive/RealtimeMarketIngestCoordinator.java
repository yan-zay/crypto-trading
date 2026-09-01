package com.tj.crypto.marketdata.archive;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.model.RawMarketMessage;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Single production entry for raw-first live market ingestion.
 * Raw bytes are fsync-acknowledged before normalization output can reach the event bus.
 */
@Component
public class RealtimeMarketIngestCoordinator {
    private final LiveMarketIngestJournal journal;
    private final MarketEventBus eventBus;

    public RealtimeMarketIngestCoordinator(LiveMarketIngestJournal journal,
                                           MarketEventBus eventBus) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        if (journal.realtimeCaptureCapability() != RealtimeCaptureCapability.WIRED) {
            throw new IllegalStateException("Live market ingest journal is not wired for realtime capture");
        }
    }

    public RawCapture retainRaw(Exchange source, MarketType marketType,
                                String symbolScope, String channel, String contentType,
                                String payload, long exchangeTimestamp, long receivedTimestamp) {
        long received = positive(receivedTimestamp, "receivedTimestamp");
        long eventTime = exchangeTimestamp > 0 ? exchangeTimestamp : received;
        RawMarketMessage raw = RawMarketMessage.of(
                new EventMetadata(Objects.requireNonNull(source, "source"), eventTime, received, null),
                Objects.requireNonNull(marketType, "marketType"), symbolScope, channel,
                contentType, payload);
        RawJournalReceipt receipt = journal.retainRaw(raw);
        return new RawCapture(receipt.rawMessageId(), receipt.message());
    }

    @SuppressWarnings("unchecked")
    public <T extends MarketEvent> CanonicalPublish<T> retainCanonicalAndPublish(
            RawCapture raw, int ordinal, T event) {
        Objects.requireNonNull(raw, "raw");
        CanonicalJournalReceipt receipt = journal.retainCanonical(raw.rawMessageId(), ordinal, event);
        T traced = (T) receipt.event();
        return new CanonicalPublish<>(traced, eventBus.publish(traced));
    }

    public void recordNormalizationFailure(RawCapture raw, String code, Throwable failure) {
        Objects.requireNonNull(raw, "raw");
        String detail = failure == null || failure.getMessage() == null
                ? "normalization failed without detail" : failure.getMessage();
        journal.recordNormalizationFailure(raw.rawMessageId(), code, detail);
    }

    private long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public record RawCapture(String rawMessageId, RawMarketMessage message) {
        public RawCapture {
            if (rawMessageId == null || rawMessageId.isBlank() || message == null) {
                throw new IllegalArgumentException("raw capture identity and message are required");
            }
        }
    }

    public record CanonicalPublish<T extends MarketEvent>(T event, boolean admitted) {
        public CanonicalPublish {
            Objects.requireNonNull(event, "event");
        }
    }
}
