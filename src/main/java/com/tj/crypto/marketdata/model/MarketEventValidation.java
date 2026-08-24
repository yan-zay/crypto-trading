package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;

final class MarketEventValidation {
    private MarketEventValidation() {}

    static void requireIdentity(Instrument instrument, EventMetadata metadata) {
        if (instrument == null || metadata == null) {
            throw new IllegalArgumentException("instrument and metadata are required");
        }
        if (metadata.source() != instrument.exchange()) {
            throw new IllegalArgumentException("metadata source must equal instrument exchange");
        }
        requireTime(metadata);
    }

    static void requireTime(EventMetadata metadata) {
        if (metadata == null) throw new IllegalArgumentException("metadata is required");
        if (metadata.source() == null) throw new IllegalArgumentException("metadata source is required");
        if (metadata.exchangeTimestamp() <= 0 || metadata.receivedTimestamp() <= 0) {
            throw new IllegalArgumentException("event timestamps must be positive");
        }
    }
}
