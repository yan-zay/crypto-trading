package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Optional;

/** Computes HHI/effective bets and w'Correlation*w from absolute exposure weights. */
public final class ConcentrationAnalyzer {
    private static final MathContext MC = MathContext.DECIMAL128;

    public ConcentrationReport analyze(Map<String, BigDecimal> absoluteExposures,
                                       CorrelationMatrix correlations) {
        throw new IllegalStateException("point-in-time exposure snapshot is required; legacy input is NOT_VERIFIED");
    }

    public ConcentrationReport analyze(ConcentrationExposureSnapshot snapshot,
                                       CorrelationMatrix correlations) {
        Objects.requireNonNull(snapshot, "snapshot").requireComplete();
        Map<String, BigDecimal> absoluteExposures = snapshot.absoluteExposures();
        Objects.requireNonNull(absoluteExposures, "absoluteExposures");
        Objects.requireNonNull(correlations, "correlations");
        if (correlations.observationTimesMs().get(correlations.observationCount() - 1)
                != snapshot.asOfTimeMs()) {
            throw new IllegalArgumentException("correlation window and exposure snapshot are not PIT aligned");
        }
        if (absoluteExposures.isEmpty()) {
            throw new IllegalArgumentException("at least one component exposure is required");
        }
        TreeMap<String, BigDecimal> sorted = new TreeMap<>();
        absoluteExposures.forEach((id, exposure) -> {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("component id is required");
            if (exposure == null || exposure.signum() < 0) {
                throw new IllegalArgumentException("absolute exposure must be non-negative for " + id);
            }
            String normalizedId = id.trim();
            if (sorted.put(normalizedId, exposure.stripTrailingZeros()) != null) {
                throw new IllegalArgumentException("duplicate normalized component id: " + normalizedId);
            }
        });
        BigDecimal total = sorted.values().stream().reduce(BigDecimal.ZERO,
                (left, right) -> left.add(right, MC));
        if (total.signum() <= 0) throw new IllegalArgumentException("total exposure must be positive");

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        sorted.forEach((id, exposure) -> weights.put(id, exposure.divide(total, MC)));
        BigDecimal hhi = weights.values().stream()
                .map(weight -> weight.multiply(weight, MC))
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal effectiveComponents = BigDecimal.ONE.divide(hhi, MC);

        Map.Entry<String, BigDecimal> largest = weights.entrySet().stream()
                .max(Map.Entry.<String, BigDecimal>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Collections.reverseOrder())))
                .orElseThrow();

        BigDecimal adjusted = adjustedConcentration(weights, correlations);
        if (adjusted.signum() < 0 && adjusted.abs().compareTo(new BigDecimal("1E-12")) <= 0) {
            adjusted = BigDecimal.ZERO;
        }
        if (adjusted.signum() < 0) {
            throw new IllegalArgumentException("correlation-adjusted concentration cannot be negative");
        }
        Optional<BigDecimal> adjustedEffectiveBets = adjusted.signum() == 0
                ? Optional.empty() : Optional.of(BigDecimal.ONE.divide(adjusted, MC));
        return new ConcentrationReport(snapshot.snapshotId(), snapshot.asOfTimeMs(),
                snapshot.valuationCurrency(), snapshot.sourceManifestSha256(), snapshot.contentSha256(),
                total, weights, hhi, effectiveComponents,
                largest.getKey(), largest.getValue(), adjusted, adjustedEffectiveBets,
                AnalyticsUse.RESEARCH_ONLY);
    }

    private BigDecimal adjustedConcentration(Map<String, BigDecimal> weights,
                                             CorrelationMatrix correlations) {
        List<String> expected = new ArrayList<>(weights.keySet());
        if (!expected.equals(correlations.componentIds())) {
            throw new IllegalArgumentException("correlation components must exactly match sorted exposure components");
        }
        BigDecimal value = BigDecimal.ZERO;
        for (String first : expected) {
            for (String second : expected) {
                value = value.add(weights.get(first).multiply(weights.get(second), MC)
                        .multiply(correlations.value(first, second), MC), MC);
            }
        }
        return value;
    }
}
