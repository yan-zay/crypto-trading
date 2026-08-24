package com.tj.crypto.marketdata.archive;

import com.tj.crypto.marketdata.model.MarketEvent;

public record CanonicalJournalReceipt(String eventId, String rawMessageId, int rawOrdinal,
                                      MarketEvent event, long durableAtEpochMillis,
                                      boolean alreadyPresent) {
    public CanonicalJournalReceipt {
        if (eventId == null || eventId.isBlank() || rawMessageId == null || rawMessageId.isBlank()
                || rawOrdinal < 0 || event == null || durableAtEpochMillis <= 0) {
            throw new IllegalArgumentException("durable canonical journal receipt is incomplete");
        }
    }
}
