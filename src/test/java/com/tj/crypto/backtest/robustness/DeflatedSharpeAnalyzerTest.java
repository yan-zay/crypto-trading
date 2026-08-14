package com.tj.crypto.backtest.robustness;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeflatedSharpeAnalyzerTest {
    private final DeflatedSharpeAnalyzer analyzer = new DeflatedSharpeAnalyzer();

    @Test
    void producesDeterministicSelectionBiasAdjustedEvidence() {
        DeflatedSharpeRequest request = new DeflatedSharpeRequest(
                "selected",
                List.of(
                        new RegisteredBacktestTrial("baseline", 0.10),
                        new RegisteredBacktestTrial("variant-a", 0.20),
                        new RegisteredBacktestTrial("variant-b", 0.30),
                        new RegisteredBacktestTrial("selected", 0.40)),
                60,
                0,
                3);

        DeflatedSharpeResult first = analyzer.analyze(request);
        DeflatedSharpeResult second = analyzer.analyze(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.registeredTrialCount()).isEqualTo(4);
        assertThat(first.expectedMaximumSharpe()).isPositive();
        assertThat(first.deflatedSharpeProbability())
                .isBetween(new BigDecimal("0.5"), BigDecimal.ONE);
        assertThat(first.limitations())
                .anyMatch(text -> text.contains("30-90 day forward paper/shadow"));
    }

    @Test
    void additionalRegisteredTrialsWithSameDispersionIncreaseDeflation() {
        DeflatedSharpeResult twoTrials = analyzer.analyze(requestWithTrials(List.of(
                new RegisteredBacktestTrial("selected", 0.4),
                new RegisteredBacktestTrial("other", -0.4))));
        DeflatedSharpeResult fourTrials = analyzer.analyze(requestWithTrials(List.of(
                new RegisteredBacktestTrial("selected", 0.4),
                new RegisteredBacktestTrial("other-1", -0.4),
                new RegisteredBacktestTrial("other-2", 0.4),
                new RegisteredBacktestTrial("other-3", -0.4))));

        assertThat(fourTrials.trialFamilySharpeVariance())
                .isEqualByComparingTo(twoTrials.trialFamilySharpeVariance());
        assertThat(fourTrials.expectedMaximumSharpe()).isGreaterThan(twoTrials.expectedMaximumSharpe());
        assertThat(fourTrials.deflatedSharpeProbability())
                .isLessThan(twoTrials.deflatedSharpeProbability());
    }

    @Test
    void detectsWhenSelectedTrialIsNotFamilyMaximum() {
        DeflatedSharpeResult result = analyzer.analyze(new DeflatedSharpeRequest(
                "selected",
                List.of(
                        new RegisteredBacktestTrial("selected", 0.2),
                        new RegisteredBacktestTrial("better", 0.8)),
                50,
                0,
                3));

        assertThat(result.limitations()).anyMatch(text -> text.contains("not the highest-Sharpe"));
    }

    @Test
    void validatesCompleteUniqueTrialRegistryAndReturnMoments() {
        assertThatThrownBy(() -> new DeflatedSharpeRequest(
                "selected",
                List.of(new RegisteredBacktestTrial("selected", 0.2)),
                20, 0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two");
        assertThatThrownBy(() -> new DeflatedSharpeRequest(
                "selected",
                List.of(
                        new RegisteredBacktestTrial("same", 0.2),
                        new RegisteredBacktestTrial("same", 0.3)),
                20, 0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new DeflatedSharpeRequest(
                "missing",
                List.of(
                        new RegisteredBacktestTrial("a", 0.2),
                        new RegisteredBacktestTrial("b", 0.3)),
                20, 0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not present");
        assertThatThrownBy(() -> analyzer.analyze(new DeflatedSharpeRequest(
                "selected",
                List.of(
                        new RegisteredBacktestTrial("selected", 2),
                        new RegisteredBacktestTrial("other", 0)),
                20, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-positive");
    }

    private DeflatedSharpeRequest requestWithTrials(List<RegisteredBacktestTrial> trials) {
        return new DeflatedSharpeRequest("selected", trials, 100, 0, 3);
    }
}
