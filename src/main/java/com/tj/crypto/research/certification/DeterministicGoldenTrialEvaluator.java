package com.tj.crypto.research.certification;

import com.tj.crypto.research.certification.GoldenExperimentRegistration.CostAssumptions;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.StrategyType;
import com.tj.crypto.research.certification.GoldenExperimentRegistration.TrialDefinition;
import com.tj.crypto.research.dataset.CanonicalBarRow;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Point-in-time, long-only evaluator for the deliberately small golden trial language.
 * Signals only use closes available at the beginning of a return interval. Costs are netted
 * on every position change and funding is charged per held bar.
 */
public final class DeterministicGoldenTrialEvaluator {
    private static final MathContext MATH = MathContext.DECIMAL128;
    private static final double BPS = 10_000D;

    public List<TrialEvaluation> evaluate(
            List<CanonicalBarRow> bars,
            List<TrialDefinition> definitions,
            CostAssumptions costs) {
        List<TrialEvaluation> results = new ArrayList<>(definitions.size());
        for (TrialDefinition definition : definitions) {
            try {
                results.add(new TrialEvaluation(definition.trialId(), TrialEvaluation.TrialRunStatus.COMPLETED,
                        evaluateOne(bars, definition, costs), List.of()));
            } catch (RuntimeException failure) {
                results.add(new TrialEvaluation(definition.trialId(), TrialEvaluation.TrialRunStatus.FAILED,
                        List.of(), List.of(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage())));
            }
        }
        return List.copyOf(results);
    }

    private List<Double> evaluateOne(List<CanonicalBarRow> bars, TrialDefinition definition, CostAssumptions costs) {
        validateParameters(definition, bars.size());
        List<Double> returns = new ArrayList<>(bars.size() - 1);
        double position = 0;
        double fillRatio = costs.partialFillRatio().doubleValue();
        double targetNotional = costs.targetNotionalQuote().doubleValue();
        double maxParticipation = costs.maxBarParticipationRatio().doubleValue();
        double turnoverCostBps = costs.takerFeeBps().doubleValue()
                + costs.slippageBps().doubleValue()
                + costs.latencyPenaltyBps().doubleValue();
        double fundingBps = costs.fundingChargeBpsPerBar().doubleValue();

        for (int index = 0; index < bars.size() - 1; index++) {
            double desired = desiredPosition(bars, index, definition);
            double quoteVolume = bars.get(index).quoteVolume().doubleValue();
            double capacityFill = Math.min(1D, quoteVolume * maxParticipation / targetNotional);
            double effectiveFill = Math.min(fillRatio, Math.max(0D, capacityFill));
            double executed = position + (desired - position) * effectiveFill;
            double turnover = Math.abs(executed - position);
            double marketReturn = bars.get(index + 1).close()
                    .divide(bars.get(index).close(), MATH).subtract(BigDecimal.ONE).doubleValue();
            double netReturn = executed * marketReturn
                    - turnover * turnoverCostBps / BPS
                    - Math.abs(executed) * fundingBps / BPS;
            if (!Double.isFinite(netReturn) || netReturn <= -1D) {
                throw new IllegalArgumentException("trial produced a non-finite or total-loss interval return at index " + index);
            }
            returns.add(netReturn);
            position = executed;
        }
        return List.copyOf(returns);
    }

    private double desiredPosition(List<CanonicalBarRow> bars, int index, TrialDefinition definition) {
        return switch (definition.strategyType()) {
            case BUY_AND_HOLD -> 1D;
            case SMA_CROSS_LONG_ONLY -> {
                int fast = definition.parameters().get("fastWindow");
                int slow = definition.parameters().get("slowWindow");
                if (index + 1 < slow) yield 0D;
                yield averageClose(bars, index - fast + 1, index) > averageClose(bars, index - slow + 1, index)
                        ? 1D : 0D;
            }
            case MOMENTUM_LONG_ONLY -> {
                int lookback = definition.parameters().get("lookback");
                if (index < lookback) yield 0D;
                yield bars.get(index).close().compareTo(bars.get(index - lookback).close()) > 0 ? 1D : 0D;
            }
        };
    }

    private double averageClose(List<CanonicalBarRow> bars, int from, int to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int index = from; index <= to; index++) sum = sum.add(bars.get(index).close());
        return sum.divide(BigDecimal.valueOf(to - from + 1L), MATH).doubleValue();
    }

    private void validateParameters(TrialDefinition definition, int barCount) {
        if (definition.strategyType() == StrategyType.BUY_AND_HOLD) {
            if (!definition.parameters().isEmpty()) throw new IllegalArgumentException("BUY_AND_HOLD parameters must be empty");
            return;
        }
        if (definition.strategyType() == StrategyType.SMA_CROSS_LONG_ONLY) {
            if (!definition.parameters().keySet().equals(java.util.Set.of("fastWindow", "slowWindow"))) {
                throw new IllegalArgumentException("SMA_CROSS requires exactly fastWindow and slowWindow");
            }
            int fast = definition.parameters().get("fastWindow");
            int slow = definition.parameters().get("slowWindow");
            if (fast < 2 || slow <= fast || slow >= barCount) {
                throw new IllegalArgumentException("SMA windows require 2 <= fastWindow < slowWindow < barCount");
            }
            return;
        }
        if (!definition.parameters().keySet().equals(java.util.Set.of("lookback"))) {
            throw new IllegalArgumentException("MOMENTUM requires exactly lookback");
        }
        int lookback = definition.parameters().get("lookback");
        if (lookback < 1 || lookback >= barCount) {
            throw new IllegalArgumentException("momentum lookback must be between 1 and barCount - 1");
        }
    }
}
