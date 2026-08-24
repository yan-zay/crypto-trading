package com.tj.crypto.autonomy.canary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Content-addressed point-in-time observation used by the canary decision layer.
 * Completeness is derived from the raw metric and regime maps, never accepted as a PASS flag.
 */
public record CanaryMetricSnapshot(
        String strategyName,
        DeploymentScope deploymentScope,
        String releaseArtifactSha256,
        String evaluationWindowId,
        Instant windowStart,
        Instant windowEnd,
        Instant observedAt,
        Instant expiresAt,
        String sourceManifestSha256,
        Map<CanaryMetric, BigDecimal> metrics,
        Map<MarketState, Long> marketStateSamples,
        String contentSha256
) {
    public CanaryMetricSnapshot {
        strategyName = CanaryCanonical.required(strategyName, "strategyName");
        deploymentScope = Objects.requireNonNull(deploymentScope, "deploymentScope");
        releaseArtifactSha256 = CanaryCanonical.required(releaseArtifactSha256, "releaseArtifactSha256")
                .toLowerCase(java.util.Locale.ROOT);
        evaluationWindowId = CanaryCanonical.required(evaluationWindowId, "evaluationWindowId");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        sourceManifestSha256 = CanaryCanonical.required(sourceManifestSha256, "sourceManifestSha256")
                .toLowerCase(java.util.Locale.ROOT);
        metrics = immutableMetrics(metrics);
        marketStateSamples = immutableStates(marketStateSamples);
        contentSha256 = CanaryCanonical.required(contentSha256, "contentSha256")
                .toLowerCase(java.util.Locale.ROOT);
    }

    public static CanaryMetricSnapshot capture(String strategyName,
                                               DeploymentScope deploymentScope,
                                               String releaseArtifactSha256,
                                               String evaluationWindowId,
                                               Instant windowStart,
                                               Instant windowEnd,
                                               Instant observedAt,
                                               Instant expiresAt,
                                               String sourceManifestSha256,
                                               Map<CanaryMetric, BigDecimal> metrics,
                                               Map<MarketState, Long> marketStateSamples) {
        Map<CanaryMetric, BigDecimal> normalizedMetrics = immutableMetrics(metrics);
        Map<MarketState, Long> normalizedStates = immutableStates(marketStateSamples);
        CanarySnapshotCompleteness completeness = deriveCompleteness(deploymentScope, windowStart, windowEnd,
                observedAt, expiresAt, sourceManifestSha256, normalizedMetrics, normalizedStates);
        String digest = digest(strategyName.trim(), deploymentScope.contentSha256(),
                releaseArtifactSha256.toLowerCase(java.util.Locale.ROOT),
                evaluationWindowId.trim(), windowStart, windowEnd, observedAt, expiresAt,
                sourceManifestSha256.toLowerCase(java.util.Locale.ROOT), normalizedMetrics, normalizedStates,
                completeness);
        return new CanaryMetricSnapshot(strategyName, deploymentScope, releaseArtifactSha256, evaluationWindowId,
                windowStart, windowEnd, observedAt, expiresAt, sourceManifestSha256,
                normalizedMetrics, normalizedStates, digest);
    }

    public CanarySnapshotCompleteness completeness() {
        return deriveCompleteness(deploymentScope, windowStart, windowEnd, observedAt, expiresAt,
                sourceManifestSha256, metrics, marketStateSamples);
    }

    public boolean contentAddressMatches() {
        return CanaryCanonical.isSha256(contentSha256) && contentSha256.equals(expectedContentSha256());
    }

    public String expectedContentSha256() {
        return digest(strategyName, deploymentScope.contentSha256(), releaseArtifactSha256,
                evaluationWindowId, windowStart, windowEnd,
                observedAt, expiresAt, sourceManifestSha256, metrics, marketStateSamples, completeness());
    }

    private static CanarySnapshotCompleteness deriveCompleteness(
            DeploymentScope deploymentScope,
            Instant windowStart,
            Instant windowEnd,
            Instant observedAt,
            Instant expiresAt,
            String sourceManifestSha256,
            Map<CanaryMetric, BigDecimal> metrics,
            Map<MarketState, Long> marketStateSamples) {
        if (!metrics.keySet().containsAll(java.util.EnumSet.allOf(CanaryMetric.class))
                || !marketStateSamples.keySet().containsAll(java.util.EnumSet.allOf(MarketState.class))) {
            return CanarySnapshotCompleteness.INCOMPLETE;
        }
        if (!deploymentScope.contentAddressMatches()
                || !CanaryCanonical.isSha256(sourceManifestSha256)
                || !windowStart.isBefore(windowEnd)
                || observedAt.isBefore(windowEnd)
                || !expiresAt.isAfter(observedAt)) {
            return CanarySnapshotCompleteness.ANOMALOUS;
        }
        for (Map.Entry<CanaryMetric, BigDecimal> entry : metrics.entrySet()) {
            BigDecimal value = entry.getValue();
            CanaryMetric metric = entry.getKey();
            if ((metric.nonNegative() && value.signum() < 0)
                    || (metric.wholeNumber() && value.stripTrailingZeros().scale() > 0)) {
                return CanarySnapshotCompleteness.ANOMALOUS;
            }
        }
        long stateSampleTotal = 0L;
        for (long count : marketStateSamples.values()) {
            if (count < 0) return CanarySnapshotCompleteness.ANOMALOUS;
            try {
                stateSampleTotal = Math.addExact(stateSampleTotal, count);
            } catch (ArithmeticException overflow) {
                return CanarySnapshotCompleteness.ANOMALOUS;
            }
        }
        BigDecimal executed = metrics.get(CanaryMetric.EXECUTED_SAMPLE_COUNT);
        if (BigDecimal.valueOf(stateSampleTotal).compareTo(executed) > 0) {
            return CanarySnapshotCompleteness.ANOMALOUS;
        }
        return CanarySnapshotCompleteness.COMPLETE;
    }

    private static String digest(String strategyName,
                                 String deploymentScopeSha256,
                                 String releaseArtifactSha256,
                                 String evaluationWindowId,
                                 Instant windowStart,
                                 Instant windowEnd,
                                 Instant observedAt,
                                 Instant expiresAt,
                                 String sourceManifestSha256,
                                 Map<CanaryMetric, BigDecimal> metrics,
                                 Map<MarketState, Long> marketStateSamples,
                                 CanarySnapshotCompleteness completeness) {
        StringJoiner canonical = new StringJoiner("\n");
        canonical.add("schema=canary-metric-snapshot/v1")
                .add("strategyName=" + strategyName)
                .add("deploymentScopeSha256=" + deploymentScopeSha256)
                .add("releaseArtifactSha256=" + releaseArtifactSha256)
                .add("evaluationWindowId=" + evaluationWindowId)
                .add("windowStart=" + windowStart.toEpochMilli())
                .add("windowEnd=" + windowEnd.toEpochMilli())
                .add("observedAt=" + observedAt.toEpochMilli())
                .add("expiresAt=" + expiresAt.toEpochMilli())
                .add("sourceManifestSha256=" + sourceManifestSha256)
                .add("completeness=" + completeness.name());
        for (CanaryMetric metric : CanaryMetric.values()) {
            BigDecimal value = metrics.get(metric);
            canonical.add("metric." + metric.name() + "="
                    + (value == null ? "<missing>" : CanaryCanonical.decimal(value)));
        }
        for (MarketState state : MarketState.values()) {
            Long count = marketStateSamples.get(state);
            canonical.add("marketState." + state.name() + "=" + (count == null ? "<missing>" : count));
        }
        return CanaryCanonical.sha256(canonical.toString());
    }

    private static Map<CanaryMetric, BigDecimal> immutableMetrics(Map<CanaryMetric, BigDecimal> source) {
        Objects.requireNonNull(source, "metrics");
        EnumMap<CanaryMetric, BigDecimal> copy = new EnumMap<>(CanaryMetric.class);
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "metric key"),
                Objects.requireNonNull(value, "metric value").stripTrailingZeros()));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<MarketState, Long> immutableStates(Map<MarketState, Long> source) {
        Objects.requireNonNull(source, "marketStateSamples");
        EnumMap<MarketState, Long> copy = new EnumMap<>(MarketState.class);
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "market state key"),
                Objects.requireNonNull(value, "market state sample count")));
        return Collections.unmodifiableMap(copy);
    }
}
