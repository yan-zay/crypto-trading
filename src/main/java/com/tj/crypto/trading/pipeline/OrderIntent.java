package com.tj.crypto.trading.pipeline;

import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.TradeSide;

import java.math.BigDecimal;
import java.time.Instant;

/** Non-authorizing output consumed by Paper today and by governed OMS adapters in future. */
public record OrderIntent(String strategyId, InstrumentId instrumentId, TradeSide side,
                          BigDecimal quantity, boolean reduceOnly, BigDecimal referencePrice,
                          String policyVersion, Instant createdAt, Instant expiresAt) {
    public OrderIntent {
        if (strategyId == null || strategyId.isBlank() || instrumentId == null || side == null
                || quantity == null || quantity.signum() <= 0 || referencePrice == null
                || referencePrice.signum() <= 0 || policyVersion == null || policyVersion.isBlank()
                || createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("complete order intent is required");
        }
        quantity = quantity.stripTrailingZeros();
        referencePrice = referencePrice.stripTrailingZeros();
    }

    public boolean executionAuthorized() {
        return false;
    }
}
