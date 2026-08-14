package com.tj.crypto.backtest.robustness;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CscvPboAnalyzerTest {
    private final CscvPboAnalyzer analyzer = new CscvPboAnalyzer();

    @Test
    void stableWinnerHasZeroPboAndDeterministicSplits() {
        List<String> trials = List.of("stable-best", "middle", "weak");
        double[][] returns = new double[8][3];
        for (double[] row : returns) {
            row[0] = 0.03;
            row[1] = 0.02;
            row[2] = 0.01;
        }

        CscvPboResult first = analyzer.analyze(trials, returns, 4);
        CscvPboResult second = analyzer.analyze(trials, returns, 4);

        assertThat(first).isEqualTo(second);
        assertThat(first.evaluatedCombinations()).isEqualTo(6);
        assertThat(first.probabilityOfBacktestOverfitting()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(first.splits()).allSatisfy(split -> {
            assertThat(split.selectedTrialId()).isEqualTo("stable-best");
            assertThat(split.overfit()).isFalse();
            assertThat(split.rankLogit()).isPositive();
        });
        assertThat(first.limitations())
                .anyMatch(text -> text.contains("30-90 day forward paper/shadow"));
    }

    @Test
    void partitionSpecialistsProduceCertainBacktestOverfitting() {
        List<int[]> specialistPartitions = List.of(
                new int[]{0, 1}, new int[]{0, 2}, new int[]{0, 3},
                new int[]{1, 2}, new int[]{1, 3}, new int[]{2, 3});
        List<String> trials = new ArrayList<>();
        for (int i = 0; i < specialistPartitions.size(); i++) trials.add("specialist-" + i);
        double[][] returns = new double[8][specialistPartitions.size()];
        for (int row = 0; row < returns.length; row++) {
            int partition = row / 2;
            for (int trial = 0; trial < specialistPartitions.size(); trial++) {
                returns[row][trial] = contains(specialistPartitions.get(trial), partition) ? 1 : -1;
            }
        }

        CscvPboResult result = analyzer.analyze(trials, returns, 4);

        assertThat(result.probabilityOfBacktestOverfitting()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.medianRankLogit()).isNegative();
        assertThat(result.splits()).allSatisfy(split -> {
            assertThat(split.outOfSampleMeanReturn()).isEqualByComparingTo(new BigDecimal("-1"));
            assertThat(split.outOfSampleRankFromWorst()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(split.overfit()).isTrue();
        });
    }

    @Test
    void tiesAreHandledConservativelyAndReported() {
        double[][] returns = new double[8][3];

        CscvPboResult result = analyzer.analyze(List.of("a", "b", "c"), returns, 4);

        assertThat(result.probabilityOfBacktestOverfitting()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.medianRankLogit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.limitations()).anyMatch(text -> text.contains("ties occurred in 6 splits"));
    }

    @Test
    void validatesRegistryMatrixAndEqualTimePartitions() {
        double[][] valid = new double[8][2];
        assertThatThrownBy(() -> analyzer.analyze(List.of("a", "b"), valid, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even");
        assertThatThrownBy(() -> analyzer.analyze(List.of("a", "b"), new double[10][2], 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("divisible");
        assertThatThrownBy(() -> analyzer.analyze(List.of("same", "same"), valid, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> analyzer.analyze(List.of("a", "b"), new double[20][2], 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 100000");
        assertThatThrownBy(() -> analyzer.analyze(
                List.of("a", "b"),
                new double[][]{{0, 0}, {0, 0}, {0, 0}, {Double.NaN, 0}},
                4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    private boolean contains(int[] values, int expected) {
        for (int value : values) if (value == expected) return true;
        return false;
    }
}
