package com.tj.crypto.trading.venue.contract;

/** Redacted action trace used to prove ordering and retry invariants without retaining secrets. */
public record VenueContractAction(int sequence, Type type, Outcome outcome, String correlationId) {
    public VenueContractAction {
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        if (type == null || outcome == null) {
            throw new IllegalArgumentException("type and outcome are required");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is required");
        }
    }

    public enum Type {
        PLACE,
        QUERY,
        CANCEL,
        STREAM_CONNECT,
        STREAM_DISCONNECT,
        STREAM_RECONNECT,
        BACKFILL,
        SNAPSHOT
    }

    public enum Outcome {
        ACCEPTED,
        AMBIGUOUS,
        KNOWN,
        PARTIALLY_FILLED,
        CANCELLED,
        REJECTED,
        RATE_LIMITED,
        CONNECTED,
        DISCONNECTED,
        COMPLETED
    }
}
