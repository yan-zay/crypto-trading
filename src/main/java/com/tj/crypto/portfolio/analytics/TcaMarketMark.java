package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Point-in-time mark plus interval market volume. Volume belongs to the interval ending at
 * {@code eventTimeMs}; callers must not pass a cumulative counter.
 */
public record TcaMarketMark(
        InstrumentId instrumentId,
        long intervalStartTimeMs,
        long eventTimeMs,
        long observedAtMs,
        BigDecimal price,
        BigDecimal intervalMarketVolumeQuantity,
        TcaQuantityUnit quantityUnit,
        String sourceEventId
) {
    public TcaMarketMark {
        Objects.requireNonNull(instrumentId, "instrumentId");
        if (intervalStartTimeMs <= 0 || eventTimeMs < intervalStartTimeMs
                || observedAtMs < eventTimeMs) {
            throw new IllegalArgumentException("invalid market mark interval/event/observation time");
        }
        if (price == null || price.signum() <= 0) throw new IllegalArgumentException("price must be positive");
        if (intervalMarketVolumeQuantity == null || intervalMarketVolumeQuantity.signum() < 0) {
            throw new IllegalArgumentException("intervalMarketVolumeQuantity must be non-negative");
        }
        if (intervalStartTimeMs == eventTimeMs && intervalMarketVolumeQuantity.signum() != 0) {
            throw new IllegalArgumentException("zero-length market interval must have zero volume");
        }
        Objects.requireNonNull(quantityUnit, "quantityUnit");
        if (sourceEventId == null || sourceEventId.isBlank()) {
            throw new IllegalArgumentException("sourceEventId is required");
        }
        sourceEventId = sourceEventId.trim();
        price = price.stripTrailingZeros();
        intervalMarketVolumeQuantity = intervalMarketVolumeQuantity.stripTrailingZeros();
    }
}
