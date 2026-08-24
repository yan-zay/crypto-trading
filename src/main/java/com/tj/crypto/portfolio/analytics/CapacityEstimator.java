package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/** Deterministic square-root market-impact capacity estimator. */
public final class CapacityEstimator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    public CapacityEstimate estimate(CapacityEstimateRequest request) {
        Objects.requireNonNull(request, "request");
        BigDecimal averageDailyNotional = request.marketData().averageDailyVolumeNotional();
        BigDecimal impactAtFullParticipation = request.impactCoefficient()
                .multiply(request.dailyVolatility(), MC).multiply(BPS, MC);
        BigDecimal ratio = request.maxImpactBps().divide(impactAtFullParticipation, MC);
        BigDecimal impactParticipation = ratio.multiply(ratio, MC).min(BigDecimal.ONE);
        BigDecimal bindingParticipation = request.maxParticipationRate().min(impactParticipation);
        int constraintComparison = impactParticipation.compareTo(request.maxParticipationRate());
        CapacityBindingConstraint constraint = constraintComparison < 0
                ? CapacityBindingConstraint.MARKET_IMPACT_LIMIT
                : constraintComparison > 0 ? CapacityBindingConstraint.PARTICIPATION_LIMIT
                : CapacityBindingConstraint.BOTH;
        BigDecimal dailyTradeCapacity = averageDailyNotional.multiply(bindingParticipation, MC);
        BigDecimal tradeCapacity = dailyTradeCapacity.multiply(request.executionHorizonDays(), MC);
        BigDecimal maximumCapital = (request.turnoverRateBasis() == TurnoverRateBasis.PER_DAY
                ? dailyTradeCapacity : tradeCapacity).divide(request.oneWayTurnoverRate(), MC);
        BigDecimal estimatedImpactBps = impactAtFullParticipation.multiply(
                sqrt(bindingParticipation), MC);
        CapacityMarketDataSnapshot marketData = request.marketData();
        return new CapacityEstimate(marketData.instrumentId(), marketData.analysisAsOfTimeMs(),
                marketData.quoteCurrency(), marketData.sourceManifestSha256(),
                marketData.averageDailyVolumeUnit(), request.turnoverRateBasis(), AnalyticsUse.RESEARCH_ONLY,
                averageDailyNotional,
                impactParticipation, bindingParticipation, tradeCapacity, maximumCapital,
                estimatedImpactBps, constraint);
    }

    private BigDecimal sqrt(BigDecimal value) {
        return value.sqrt(MC).stripTrailingZeros();
    }
}
