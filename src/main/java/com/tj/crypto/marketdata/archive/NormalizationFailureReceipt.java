package com.tj.crypto.marketdata.archive;

public record NormalizationFailureReceipt(String failureId, String rawMessageId, String code,
                                          String detail, long durableAtEpochMillis,
                                          boolean alreadyPresent) {
    public NormalizationFailureReceipt {
        if (failureId == null || failureId.isBlank() || rawMessageId == null || rawMessageId.isBlank()
                || code == null || code.isBlank() || detail == null || detail.isBlank()
                || durableAtEpochMillis <= 0) {
            throw new IllegalArgumentException("durable normalization failure receipt is incomplete");
        }
    }
}
