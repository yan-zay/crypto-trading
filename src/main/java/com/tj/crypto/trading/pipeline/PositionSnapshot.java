package com.tj.crypto.trading.pipeline;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;
import java.time.Instant;

/** Point-in-time signed position fact used by the common target pipeline. */
public record PositionSnapshot(InstrumentId instrumentId, BigDecimal signedQuantity,
                               String quantityUnit, String manifestSha256,
                               Instant observedAt, Instant expiresAt) {
    public PositionSnapshot {
        if (instrumentId == null || signedQuantity == null || quantityUnit == null
                || quantityUnit.isBlank() || manifestSha256 == null
                || !manifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("complete position snapshot is required");
        }
        if (observedAt == null || expiresAt == null || !expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("position snapshot requires a positive TTL");
        }
        signedQuantity = signedQuantity.stripTrailingZeros();
        quantityUnit = quantityUnit.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
