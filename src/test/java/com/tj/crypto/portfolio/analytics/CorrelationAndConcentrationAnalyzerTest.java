package com.tj.crypto.portfolio.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationAndConcentrationAnalyzerTest {
    private final ReturnCorrelationAnalyzer correlationAnalyzer = new ReturnCorrelationAnalyzer();
    private final ConcentrationAnalyzer concentrationAnalyzer = new ConcentrationAnalyzer();

    @Test
    void computesSortedSymmetricCorrelationAndCorrelationAdjustedConcentration() {
        CorrelationMatrix matrix = correlationAnalyzer.analyze(List.of(
                series("beta", "-1", "-2", "-3", "-4"),
                series("alpha", "1", "2", "3", "4")));

        assertThat(matrix.componentIds()).containsExactly("alpha", "beta");
        assertThat(matrix.value("alpha", "alpha")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(matrix.value("alpha", "beta")).isEqualByComparingTo("-1");
        assertThat(matrix.value("beta", "alpha")).isEqualByComparingTo("-1");

        Map<String, BigDecimal> exposures = Map.of("alpha", bd("60"), "beta", bd("40"));
        ConcentrationReport report = concentrationAnalyzer.analyze(snapshot(exposures, matrix), matrix);
        assertThat(report.normalizedWeights()).containsEntry("alpha", bd("0.6"))
                .containsEntry("beta", bd("0.4"));
        assertThat(report.herfindahlIndex()).isEqualByComparingTo("0.52");
        assertThat(report.effectiveComponents()).isEqualByComparingTo(BigDecimal.ONE.divide(bd("0.52"),
                java.math.MathContext.DECIMAL128));
        assertThat(report.correlationAdjustedConcentration()).isEqualByComparingTo("0.04");
        assertThat(report.correlationAdjustedEffectiveBets()).contains(bd("25"));
        assertThat(report.largestComponentId()).isEqualTo("alpha");
    }

    @Test
    void correlationPropertiesHoldAcrossDeterministicSeriesFamily() {
        for (int seed = 1; seed <= 25; seed++) {
            List<BigDecimal> first = new java.util.ArrayList<>();
            List<BigDecimal> second = new java.util.ArrayList<>();
            for (int i = 0; i < 30; i++) {
                first.add(BigDecimal.valueOf(((i * (seed + 1) + i * i) % 17) - 8));
                second.add(BigDecimal.valueOf((((i + 3) * (seed + 5) + i * i) % 19) - 9));
            }
            CorrelationMatrix matrix = correlationAnalyzer.analyze(List.of(
                    series("x", first), series("y", second)));
            BigDecimal xy = matrix.value("x", "y");
            assertThat(xy).isBetween(BigDecimal.ONE.negate(), BigDecimal.ONE);
            assertThat(xy).isEqualByComparingTo(matrix.value("y", "x"));
            assertThat(matrix.value("x", "x")).isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    @Test
    void hhiBoundsHoldForPositiveExposureFamilies() {
        for (int count = 1; count <= 20; count++) {
            Map<String, BigDecimal> exposures = new LinkedHashMap<>();
            for (int i = 1; i <= count; i++) exposures.put("c-" + i, BigDecimal.valueOf(i));
            CorrelationMatrix identity = identityMatrix(exposures.keySet().stream().sorted().toList());
            ConcentrationReport report = concentrationAnalyzer.analyze(snapshot(exposures, identity), identity);
            assertThat(report.herfindahlIndex()).isBetween(
                    BigDecimal.ONE.divide(BigDecimal.valueOf(count), java.math.MathContext.DECIMAL128),
                    BigDecimal.ONE);
            assertThat(report.normalizedWeights().values().stream().reduce(BigDecimal.ZERO,
                    BigDecimal::add)).isCloseTo(BigDecimal.ONE,
                    org.assertj.core.data.Offset.offset(new BigDecimal("1E-32")));
        }
    }

    @Test
    void rejectsMisalignedZeroVarianceAndMismatchedCorrelationInputs() {
        assertThatThrownBy(() -> correlationAnalyzer.analyze(List.of(
                series("a", "1", "2"), series("b", "1", "2", "3"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aligned");
        assertThatThrownBy(() -> correlationAnalyzer.analyze(List.of(series("a", "1", "1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero variance");
        CorrelationMatrix matrix = correlationAnalyzer.analyze(List.of(
                series("a", "1", "2"), series("b", "2", "4")));
        assertThatThrownBy(() -> concentrationAnalyzer.analyze(
                snapshot(Map.of("a", bd("1")), matrix), matrix))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly match");

        ReturnSeries first = new ReturnSeries("x", List.of(1L, 2L), List.of(bd("1"), bd("2")),
                "a".repeat(64));
        ReturnSeries shifted = new ReturnSeries("y", List.of(2L, 3L), List.of(bd("2"), bd("3")),
                "b".repeat(64));
        assertThatThrownBy(() -> correlationAnalyzer.analyze(List.of(first, shifted)))
                .hasMessageContaining("observation times");

        assertThatThrownBy(() -> concentrationAnalyzer.analyze(Map.of("a", bd("1")), matrix))
                .hasMessageContaining("NOT_VERIFIED");
    }

    private ReturnSeries series(String id, String... returns) {
        return series(id, java.util.Arrays.stream(returns).map(BigDecimal::new).toList());
    }

    private ReturnSeries series(String id, List<BigDecimal> returns) {
        List<Long> times = java.util.stream.LongStream.rangeClosed(1, returns.size()).boxed().toList();
        return new ReturnSeries(id, times, returns, "a".repeat(64));
    }

    private CorrelationMatrix identityMatrix(List<String> ids) {
        List<List<BigDecimal>> values = new java.util.ArrayList<>();
        for (int row = 0; row < ids.size(); row++) {
            List<BigDecimal> line = new java.util.ArrayList<>();
            for (int column = 0; column < ids.size(); column++) {
                line.add(row == column ? BigDecimal.ONE : BigDecimal.ZERO);
            }
            values.add(line);
        }
        return new CorrelationMatrix(ids, values, 2, List.of(1L, 2L),
                ids.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> "a".repeat(64))));
    }

    private ConcentrationExposureSnapshot snapshot(Map<String, BigDecimal> exposures,
                                                   CorrelationMatrix matrix) {
        long asOf = matrix.observationTimesMs().get(matrix.observationCount() - 1);
        return ConcentrationExposureSnapshot.create("concentration-snapshot", asOf, "USDT",
                "f".repeat(64), SnapshotCompleteness.COMPLETE, exposures);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
