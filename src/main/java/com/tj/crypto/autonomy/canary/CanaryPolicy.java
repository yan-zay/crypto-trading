package com.tj.crypto.autonomy.canary;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Immutable, content-addressed policy. The only public policy is the conservative v1 ladder;
 * callers cannot inject thresholds or capital rungs into an evaluation request.
 */
public final class CanaryPolicy {
    private static final String POLICY_ID = "bounded-autonomy-canary";
    private static final long POLICY_VERSION = 1L;

    private final DeploymentScope deploymentScope;
    private final Duration minimumEvaluationWindow;
    private final long minimumSampleCount;
    private final Map<MarketState, Long> minimumMarketStateSamples;
    private final BigDecimal minimumNetReturnAdvantageBps;
    private final BigDecimal maximumCostRegressionBps;
    private final BigDecimal maximumDrawdownRegressionBps;
    private final BigDecimal maximumTcaRegressionBps;
    private final long maximumUnexplainedReconciliationEvents;
    private final List<CapitalRung> capitalLadder;
    private final String contentSha256;

    private CanaryPolicy(DeploymentScope deploymentScope) {
        this.deploymentScope = java.util.Objects.requireNonNull(deploymentScope, "deploymentScope");
        this.minimumEvaluationWindow = Duration.ofDays(30);
        this.minimumSampleCount = 1_000L;
        EnumMap<MarketState, Long> coverage = new EnumMap<>(MarketState.class);
        for (MarketState state : MarketState.values()) coverage.put(state, 100L);
        this.minimumMarketStateSamples = Collections.unmodifiableMap(coverage);
        this.minimumNetReturnAdvantageBps = new BigDecimal("5");
        this.maximumCostRegressionBps = new BigDecimal("1");
        this.maximumDrawdownRegressionBps = new BigDecimal("10");
        this.maximumTcaRegressionBps = new BigDecimal("1");
        this.maximumUnexplainedReconciliationEvents = 0L;
        this.capitalLadder = List.of(
                new CapitalRung("TINY_1_BPS", 1, 1),
                new CapitalRung("TINY_5_BPS", 2, 5),
                new CapitalRung("TINY_10_BPS", 3, 10),
                new CapitalRung("BOUNDED_25_BPS", 4, 25));
        this.contentSha256 = CanaryCanonical.sha256(canonicalPayload());
    }

    public static CanaryPolicy conservativeV1(DeploymentScope deploymentScope) {
        return new CanaryPolicy(deploymentScope);
    }

    public DeploymentScope deploymentScope() {
        return deploymentScope;
    }

    public String policyId() {
        return POLICY_ID;
    }

    public long policyVersion() {
        return POLICY_VERSION;
    }

    public Duration minimumEvaluationWindow() {
        return minimumEvaluationWindow;
    }

    public long minimumSampleCount() {
        return minimumSampleCount;
    }

    public Map<MarketState, Long> minimumMarketStateSamples() {
        return minimumMarketStateSamples;
    }

    public BigDecimal minimumNetReturnAdvantageBps() {
        return minimumNetReturnAdvantageBps;
    }

    public BigDecimal maximumCostRegressionBps() {
        return maximumCostRegressionBps;
    }

    public BigDecimal maximumDrawdownRegressionBps() {
        return maximumDrawdownRegressionBps;
    }

    public BigDecimal maximumTcaRegressionBps() {
        return maximumTcaRegressionBps;
    }

    public long maximumUnexplainedReconciliationEvents() {
        return maximumUnexplainedReconciliationEvents;
    }

    public List<CapitalRung> capitalLadder() {
        return capitalLadder;
    }

    public String contentSha256() {
        return contentSha256;
    }

    public boolean contentAddressMatches() {
        return deploymentScope.contentAddressMatches()
                && contentSha256.equals(CanaryCanonical.sha256(canonicalPayload()));
    }

    public CapitalRung firstRung() {
        return capitalLadder.get(0);
    }

    public Optional<CapitalRung> rung(String rungId) {
        if (rungId == null) return Optional.empty();
        return capitalLadder.stream().filter(rung -> rung.rungId().equals(rungId)).findFirst();
    }

    public Optional<CapitalRung> next(String rungId) {
        Optional<CapitalRung> current = rung(rungId);
        if (current.isEmpty() || current.get().ordinal() >= capitalLadder.size()) return Optional.empty();
        return Optional.of(capitalLadder.get(current.get().ordinal()));
    }

    public Optional<CapitalRung> previous(String rungId) {
        Optional<CapitalRung> current = rung(rungId);
        if (current.isEmpty() || current.get().ordinal() == 1) return Optional.empty();
        return Optional.of(capitalLadder.get(current.get().ordinal() - 2));
    }

    private String canonicalPayload() {
        StringJoiner canonical = new StringJoiner("\n");
        canonical.add("schema=canary-policy/v1")
                .add("policyId=" + POLICY_ID)
                .add("policyVersion=" + POLICY_VERSION)
                .add("deploymentScopeSha256=" + deploymentScope.contentSha256())
                .add("minimumEvaluationWindowSeconds=" + minimumEvaluationWindow.toSeconds())
                .add("minimumSampleCount=" + minimumSampleCount)
                .add("minimumNetReturnAdvantageBps=" + CanaryCanonical.decimal(minimumNetReturnAdvantageBps))
                .add("maximumCostRegressionBps=" + CanaryCanonical.decimal(maximumCostRegressionBps))
                .add("maximumDrawdownRegressionBps=" + CanaryCanonical.decimal(maximumDrawdownRegressionBps))
                .add("maximumTcaRegressionBps=" + CanaryCanonical.decimal(maximumTcaRegressionBps))
                .add("maximumUnexplainedReconciliationEvents=" + maximumUnexplainedReconciliationEvents);
        for (MarketState state : MarketState.values()) {
            canonical.add("minimumMarketStateSamples." + state.name() + "="
                    + minimumMarketStateSamples.get(state));
        }
        for (CapitalRung rung : capitalLadder) {
            canonical.add("capitalRung=" + rung.ordinal() + ":" + rung.rungId() + ":"
                    + rung.maxApprovedCapitalBps());
        }
        return canonical.toString();
    }
}
