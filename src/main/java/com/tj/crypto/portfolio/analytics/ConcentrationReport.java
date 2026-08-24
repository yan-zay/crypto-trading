package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Standalone and correlation-adjusted concentration diagnostics. */
public record ConcentrationReport(
        String snapshotId,
        long asOfTimeMs,
        String valuationCurrency,
        String sourceManifestSha256,
        String contentSha256,
        BigDecimal totalAbsoluteExposure,
        Map<String, BigDecimal> normalizedWeights,
        BigDecimal herfindahlIndex,
        BigDecimal effectiveComponents,
        String largestComponentId,
        BigDecimal largestWeight,
        BigDecimal correlationAdjustedConcentration,
        Optional<BigDecimal> correlationAdjustedEffectiveBets,
        AnalyticsUse permittedUse
) {
    public ConcentrationReport {
        if (permittedUse != AnalyticsUse.RESEARCH_ONLY) {
            throw new IllegalArgumentException("concentration reports are RESEARCH_ONLY");
        }
        correlationAdjustedEffectiveBets = correlationAdjustedEffectiveBets == null
                ? Optional.empty() : correlationAdjustedEffectiveBets;
        normalizedWeights = Collections.unmodifiableMap(new LinkedHashMap<>(normalizedWeights));
    }
}
