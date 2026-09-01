package com.tj.crypto.trading.pipeline;

import java.math.BigDecimal;

/** Deterministic risk output; downstream execution may reduce but never enlarge this target. */
public record RiskAdjustedTarget(PortfolioTarget target, BigDecimal maxOrderQuantity,
                                 String riskPolicySha256) {
    public RiskAdjustedTarget {
        if (target == null || maxOrderQuantity == null || maxOrderQuantity.signum() <= 0
                || riskPolicySha256 == null || !riskPolicySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("valid risk-adjusted target is required");
        }
        maxOrderQuantity = maxOrderQuantity.stripTrailingZeros();
    }
}
