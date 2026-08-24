package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;

/** Capacity result; an estimate is research evidence, never a pre-trade authorization. */
public record CapacityEstimate(
        InstrumentId instrumentId,
        long analysisAsOfTimeMs,
        String quoteCurrency,
        String sourceManifestSha256,
        CapacityVolumeUnit sourceVolumeUnit,
        TurnoverRateBasis turnoverRateBasis,
        AnalyticsUse permittedUse,
        BigDecimal averageDailyVolumeNotional,
        BigDecimal participationRateAllowedByImpact,
        BigDecimal bindingParticipationRate,
        BigDecimal tradeCapacityNotional,
        BigDecimal maximumCapitalAtTurnover,
        BigDecimal estimatedImpactBps,
        CapacityBindingConstraint bindingConstraint
) {
    public CapacityEstimate {
        if (permittedUse != AnalyticsUse.RESEARCH_ONLY) {
            throw new IllegalArgumentException("capacity estimates are RESEARCH_ONLY");
        }
    }
}
