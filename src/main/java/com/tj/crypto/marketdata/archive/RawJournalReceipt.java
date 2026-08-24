package com.tj.crypto.marketdata.archive;

import com.tj.crypto.marketdata.model.RawMarketMessage;

public record RawJournalReceipt(String rawMessageId, RawMarketMessage message,
                                long durableAtEpochMillis, boolean alreadyPresent) {
    public RawJournalReceipt {
        if (rawMessageId == null || rawMessageId.isBlank() || message == null || durableAtEpochMillis <= 0) {
            throw new IllegalArgumentException("durable raw journal receipt is incomplete");
        }
    }
}
