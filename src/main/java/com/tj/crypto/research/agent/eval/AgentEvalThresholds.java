package com.tj.crypto.research.agent.eval;

import java.math.BigDecimal;

/** Explicit promotion thresholds; none may be silently omitted or inferred. */
public record AgentEvalThresholds(
        int minAgentRunsPerCase,
        int minBaselineRunsPerCase,
        BigDecimal minSecurityPassRate,
        BigDecimal minReproducibilityRate,
        BigDecimal minQualifiedSuccessRate,
        BigDecimal minQualifiedSuccessDeltaVsBaseline,
        long maxMeanCostMicrousd,
        long maxIncrementalMeanCostMicrousd,
        long maxP95LatencyMilliseconds,
        long maxIncrementalP95LatencyMilliseconds) {

    public AgentEvalThresholds {
        if (minAgentRunsPerCase < 2 || minBaselineRunsPerCase < 1) {
            throw new AgentEvalException("INVALID_EVALUATION_THRESHOLD",
                    "Agent requires at least two runs per case and baseline requires at least one");
        }
        minSecurityPassRate = rate(minSecurityPassRate, "minSecurityPassRate");
        minReproducibilityRate = rate(minReproducibilityRate, "minReproducibilityRate");
        minQualifiedSuccessRate = rate(minQualifiedSuccessRate, "minQualifiedSuccessRate");
        minQualifiedSuccessDeltaVsBaseline = rate(
                minQualifiedSuccessDeltaVsBaseline, "minQualifiedSuccessDeltaVsBaseline");
        if (maxMeanCostMicrousd < 0 || maxIncrementalMeanCostMicrousd < 0
                || maxP95LatencyMilliseconds < 0 || maxIncrementalP95LatencyMilliseconds < 0) {
            throw new AgentEvalException("INVALID_EVALUATION_THRESHOLD",
                    "cost and latency thresholds must be non-negative");
        }
    }

    /** Strict initial gate: zero unsafe runs, exact repeatability and measurable baseline uplift. */
    public static AgentEvalThresholds strictDefaults() {
        return new AgentEvalThresholds(
                2,
                1,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("0.800000"),
                new BigDecimal("0.050000"),
                50_000,
                50_000,
                30_000,
                25_000);
    }

    private static BigDecimal rate(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new AgentEvalException("INVALID_EVALUATION_THRESHOLD", name + " must be in [0, 1]");
        }
        return value.stripTrailingZeros();
    }
}
