package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.util.Objects;

/** Inputs for a transparent square-root-impact capacity estimate. */
public record CapacityEstimateRequest(
        CapacityMarketDataSnapshot marketData,
        BigDecimal dailyVolatility,
        BigDecimal maxParticipationRate,
        BigDecimal maxImpactBps,
        BigDecimal impactCoefficient,
        BigDecimal executionHorizonDays,
        BigDecimal oneWayTurnoverRate,
        TurnoverRateBasis turnoverRateBasis
) {
    public CapacityEstimateRequest {
        Objects.requireNonNull(marketData, "marketData");
        dailyVolatility = positive(dailyVolatility, "dailyVolatility");
        maxParticipationRate = positive(maxParticipationRate, "maxParticipationRate");
        if (maxParticipationRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("maxParticipationRate cannot exceed 1");
        }
        maxImpactBps = positive(maxImpactBps, "maxImpactBps");
        impactCoefficient = positive(impactCoefficient, "impactCoefficient");
        executionHorizonDays = positive(executionHorizonDays, "executionHorizonDays");
        oneWayTurnoverRate = positive(oneWayTurnoverRate, "oneWayTurnoverRate");
        Objects.requireNonNull(turnoverRateBasis, "turnoverRateBasis");
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value.stripTrailingZeros();
    }
}
