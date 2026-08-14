package com.tj.crypto.backtest.robustness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bailey/Lopez de Prado-style Deflated Sharpe calculation using an explicitly registered
 * trial family. The family variance is the population variance of all registered trial Sharpes.
 */
public final class DeflatedSharpeAnalyzer {
    private static final double EULER_MASCHERONI = 0.5772156649015329;

    public DeflatedSharpeResult analyze(DeflatedSharpeRequest request) {
        Objects.requireNonNull(request, "request");
        List<RegisteredBacktestTrial> trials = request.registeredTrials();
        RegisteredBacktestTrial selected = trials.stream()
                .filter(trial -> trial.trialId().equals(request.selectedTrialId()))
                .findFirst()
                .orElseThrow();

        double mean = trials.stream().mapToDouble(RegisteredBacktestTrial::sharpeRatio).average().orElseThrow();
        double familyVariance = trials.stream()
                .mapToDouble(trial -> square(trial.sharpeRatio() - mean))
                .average()
                .orElseThrow();
        int trialCount = trials.size();
        double firstQuantile = RobustnessStatistics.inverseNormalCdf(1D - 1D / trialCount);
        double secondQuantile = RobustnessStatistics.inverseNormalCdf(1D - 1D / (trialCount * Math.E));
        double expectedMaximumSharpe = Math.sqrt(familyVariance)
                * ((1 - EULER_MASCHERONI) * firstQuantile + EULER_MASCHERONI * secondQuantile);

        double selectedSharpe = selected.sharpeRatio();
        double estimatorVariance = 1
                - request.selectedReturnSkewness() * selectedSharpe
                + ((request.selectedReturnPearsonKurtosis() - 1) / 4D) * square(selectedSharpe);
        if (!(estimatorVariance > 0) || !Double.isFinite(estimatorVariance)) {
            throw new IllegalArgumentException("selected return moments imply a non-positive Sharpe estimator variance");
        }
        double statistic = (selectedSharpe - expectedMaximumSharpe)
                * Math.sqrt(request.selectedObservationCount() - 1D)
                / Math.sqrt(estimatorVariance);
        double probability = RobustnessStatistics.normalCdf(statistic);

        List<String> limitations = new ArrayList<>();
        limitations.add("The registered trial family must include every attempted strategy and parameter search");
        limitations.add("The expected-maximum correction assumes independent trials; correlated trials require an effective trial count");
        limitations.add(RobustnessStatistics.FORWARD_VALIDATION_LIMITATION);
        double highestSharpe = trials.stream().mapToDouble(RegisteredBacktestTrial::sharpeRatio).max().orElseThrow();
        if (selectedSharpe < highestSharpe) {
            limitations.add("The selected trial is not the highest-Sharpe member of the registered family");
        }
        if (familyVariance == 0) {
            limitations.add("All registered trial Sharpes are identical; selection-bias deflation has zero dispersion");
        }

        return new DeflatedSharpeResult(
                selected.trialId(),
                trialCount,
                request.selectedObservationCount(),
                RobustnessStatistics.decimal(selectedSharpe),
                RobustnessStatistics.decimal(familyVariance),
                RobustnessStatistics.decimal(expectedMaximumSharpe),
                RobustnessStatistics.decimal(probability),
                probability >= 0.95,
                limitations);
    }

    private double square(double value) {
        return value * value;
    }
}
