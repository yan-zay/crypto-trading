package com.tj.crypto.trading.pipeline;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;
import java.time.Instant;

/** Signed target quantity: positive=long, negative=short, zero=flat. */
public record PortfolioTarget(String strategyId, InstrumentId instrumentId,
                              BigDecimal targetQuantity, String quantityUnit,
                              String policyVersion, String sourceArtifactSha256,
                              Instant createdAt, Instant expiresAt) {
    public PortfolioTarget {
        if (strategyId == null || strategyId.isBlank() || instrumentId == null
                || targetQuantity == null || quantityUnit == null || quantityUnit.isBlank()
                || policyVersion == null || policyVersion.isBlank()
                || sourceArtifactSha256 == null
                || !sourceArtifactSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("complete content-bound portfolio target is required");
        }
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("portfolio target requires a positive TTL");
        }
        targetQuantity = targetQuantity.stripTrailingZeros();
        strategyId = strategyId.trim();
        quantityUnit = quantityUnit.trim().toUpperCase(java.util.Locale.ROOT);
        policyVersion = policyVersion.trim();
    }
}
