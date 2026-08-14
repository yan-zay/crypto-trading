package com.tj.crypto.research.certification;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Immutable, manifest-bound pre-registration for the complete tried-strategy family.
 * Results not named here are forbidden; named trials may not be omitted from the report.
 */
public record GoldenExperimentRegistration(
        String schemaVersion,
        String registrationId,
        long registeredAtEpochMillis,
        String datasetId,
        String datasetManifestSha256,
        String selectedTrialId,
        List<TrialDefinition> trials,
        int purgedFoldCount,
        int purgeBars,
        int embargoBars,
        int cscvPartitionCount,
        CostAssumptions costAssumptions,
        CertificationThresholds thresholds
) {
    public static final String CURRENT_SCHEMA_VERSION = "1";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public GoldenExperimentRegistration {
        schemaVersion = required(schemaVersion, "schemaVersion");
        registrationId = required(registrationId, "registrationId");
        datasetId = required(datasetId, "datasetId");
        datasetManifestSha256 = required(datasetManifestSha256, "datasetManifestSha256");
        selectedTrialId = required(selectedTrialId, "selectedTrialId");
        if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported registration schemaVersion: " + schemaVersion);
        }
        if (registeredAtEpochMillis <= 0) throw new IllegalArgumentException("registeredAtEpochMillis must be positive");
        if (!SHA_256.matcher(datasetManifestSha256).matches()) {
            throw new IllegalArgumentException("datasetManifestSha256 must be lowercase SHA-256");
        }
        if (trials == null) throw new IllegalArgumentException("trials are required");
        trials = List.copyOf(trials);
        if (trials.size() < 3) {
            throw new IllegalArgumentException("trial family must include a candidate, buy-and-hold and simple-rule baseline");
        }
        Set<String> ids = new HashSet<>();
        int candidates = 0;
        int buyAndHold = 0;
        int simpleRules = 0;
        for (TrialDefinition trial : trials) {
            if (trial == null) throw new IllegalArgumentException("trials must not contain null");
            if (!ids.add(trial.trialId())) throw new IllegalArgumentException("duplicate trialId: " + trial.trialId());
            if (trial.role() == TrialRole.GOLDEN_CANDIDATE) candidates++;
            if (trial.role() == TrialRole.BUY_AND_HOLD_BASELINE) buyAndHold++;
            if (trial.role() == TrialRole.SIMPLE_RULE_BASELINE) simpleRules++;
        }
        if (candidates != 1 || buyAndHold != 1 || simpleRules < 1) {
            throw new IllegalArgumentException("registration requires exactly one candidate, exactly one buy-and-hold, and a simple-rule baseline");
        }
        String normalizedSelectedTrialId = selectedTrialId;
        TrialDefinition selected = trials.stream().filter(trial -> trial.trialId().equals(normalizedSelectedTrialId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("selectedTrialId is not registered"));
        if (selected.role() != TrialRole.GOLDEN_CANDIDATE || selected.strategyType() != StrategyType.SMA_CROSS_LONG_ONLY) {
            throw new IllegalArgumentException("selected trial must be the simple SMA-cross golden candidate");
        }
        if (purgedFoldCount < 2) throw new IllegalArgumentException("purgedFoldCount must be at least 2");
        if (purgeBars < 0 || embargoBars < 0) throw new IllegalArgumentException("purgeBars and embargoBars must be non-negative");
        if (cscvPartitionCount < 4 || cscvPartitionCount > 18 || cscvPartitionCount % 2 != 0) {
            throw new IllegalArgumentException("cscvPartitionCount must be even and between 4 and 18");
        }
        if (costAssumptions == null || thresholds == null) {
            throw new IllegalArgumentException("costAssumptions and thresholds are required");
        }
    }

    public record TrialDefinition(
            String trialId,
            TrialRole role,
            StrategyType strategyType,
            String hypothesis,
            Map<String, Integer> parameters
    ) {
        public TrialDefinition {
            trialId = required(trialId, "trialId");
            hypothesis = required(hypothesis, "hypothesis");
            if (role == null || strategyType == null) throw new IllegalArgumentException("trial role and strategyType are required");
            if (parameters == null) throw new IllegalArgumentException("trial parameters are required");
            parameters = Map.copyOf(new TreeMap<>(parameters));
            if (role == TrialRole.BUY_AND_HOLD_BASELINE && strategyType != StrategyType.BUY_AND_HOLD) {
                throw new IllegalArgumentException("buy-and-hold role must use BUY_AND_HOLD strategyType");
            }
            if (role == TrialRole.SIMPLE_RULE_BASELINE && strategyType == StrategyType.BUY_AND_HOLD) {
                throw new IllegalArgumentException("simple-rule baseline may not duplicate buy-and-hold");
            }
        }
    }

    public record CostAssumptions(
            BigDecimal takerFeeBps,
            BigDecimal slippageBps,
            long decisionLatencyMillis,
            BigDecimal latencyPenaltyBps,
            BigDecimal fundingChargeBpsPerBar,
            BigDecimal partialFillRatio,
            BigDecimal targetNotionalQuote,
            BigDecimal hardCapacityQuote,
            BigDecimal maxBarParticipationRatio
    ) {
        public CostAssumptions {
            nonNegative(takerFeeBps, "takerFeeBps");
            nonNegative(slippageBps, "slippageBps");
            nonNegative(latencyPenaltyBps, "latencyPenaltyBps");
            nonNegative(fundingChargeBpsPerBar, "fundingChargeBpsPerBar");
            if (decisionLatencyMillis < 0) throw new IllegalArgumentException("decisionLatencyMillis must be non-negative");
            positiveAtMostOne(partialFillRatio, "partialFillRatio");
            positive(targetNotionalQuote, "targetNotionalQuote");
            positive(hardCapacityQuote, "hardCapacityQuote");
            positiveAtMostOne(maxBarParticipationRatio, "maxBarParticipationRatio");
            if (targetNotionalQuote.compareTo(hardCapacityQuote) > 0) {
                throw new IllegalArgumentException("targetNotionalQuote exceeds preregistered hardCapacityQuote");
            }
        }
    }

    public record CertificationThresholds(
            BigDecimal minimumDeflatedSharpeProbability,
            BigDecimal maximumPbo,
            BigDecimal minimumCandidateCumulativeReturn,
            BigDecimal minimumExcessOverBuyAndHold,
            BigDecimal minimumExcessOverSimpleRule
    ) {
        public CertificationThresholds {
            betweenZeroAndOne(minimumDeflatedSharpeProbability, "minimumDeflatedSharpeProbability");
            betweenZeroAndOne(maximumPbo, "maximumPbo");
            nonNegative(minimumCandidateCumulativeReturn, "minimumCandidateCumulativeReturn");
            nonNegative(minimumExcessOverBuyAndHold, "minimumExcessOverBuyAndHold");
            nonNegative(minimumExcessOverSimpleRule, "minimumExcessOverSimpleRule");
        }
    }

    public enum TrialRole {
        GOLDEN_CANDIDATE,
        BUY_AND_HOLD_BASELINE,
        SIMPLE_RULE_BASELINE,
        ALTERNATIVE_OR_PARAMETER_TRIAL
    }

    public enum StrategyType {
        BUY_AND_HOLD,
        SMA_CROSS_LONG_ONLY,
        MOMENTUM_LONG_ONLY
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static void positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void nonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(field + " must be non-negative");
    }

    private static void positiveAtMostOne(BigDecimal value, String field) {
        positive(value, field);
        if (value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException(field + " must be at most one");
    }

    private static void betweenZeroAndOne(BigDecimal value, String field) {
        nonNegative(value, field);
        if (value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException(field + " must be at most one");
    }
}
