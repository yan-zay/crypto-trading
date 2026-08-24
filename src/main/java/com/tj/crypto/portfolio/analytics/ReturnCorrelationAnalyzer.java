package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Computes a deterministic, aligned Pearson return-correlation matrix. */
public final class ReturnCorrelationAnalyzer {
    private static final int OUTPUT_SCALE = 12;
    private static final MathContext MC = MathContext.DECIMAL128;

    public CorrelationMatrix analyze(List<ReturnSeries> input) {
        Objects.requireNonNull(input, "input");
        if (input.isEmpty()) throw new IllegalArgumentException("at least one return series is required");
        List<ReturnSeries> series = input.stream()
                .peek(value -> Objects.requireNonNull(value, "return series"))
                .sorted(Comparator.comparing(ReturnSeries::componentId))
                .toList();
        Set<String> ids = new HashSet<>();
        int observations = series.get(0).returns().size();
        for (ReturnSeries value : series) {
            if (!ids.add(value.componentId())) {
                throw new IllegalArgumentException("duplicate componentId: " + value.componentId());
            }
            if (value.returns().size() != observations) {
                throw new IllegalArgumentException("return series must be point-in-time aligned and equally sized");
            }
            if (!value.observationTimesMs().equals(series.get(0).observationTimesMs())) {
                throw new IllegalArgumentException("return series must have exactly aligned observation times");
            }
            requireVariance(value);
        }

        List<List<BigDecimal>> matrix = new ArrayList<>();
        for (int row = 0; row < series.size(); row++) {
            List<BigDecimal> values = new ArrayList<>();
            for (int column = 0; column < series.size(); column++) {
                values.add(row == column ? BigDecimal.ONE : correlation(series.get(row), series.get(column)));
            }
            matrix.add(values);
        }
        return new CorrelationMatrix(series.stream().map(ReturnSeries::componentId).toList(),
                matrix, observations, series.get(0).observationTimesMs(),
                series.stream().collect(java.util.stream.Collectors.toMap(
                        ReturnSeries::componentId, ReturnSeries::dataManifestSha256,
                        (left, right) -> { throw new IllegalStateException("duplicate componentId"); },
                        java.util.LinkedHashMap::new)));
    }

    private BigDecimal correlation(ReturnSeries first, ReturnSeries second) {
        BigDecimal count = BigDecimal.valueOf(first.returns().size());
        BigDecimal firstMean = first.returns().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(count, MC);
        BigDecimal secondMean = second.returns().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(count, MC);
        BigDecimal covariance = BigDecimal.ZERO;
        BigDecimal firstSquares = BigDecimal.ZERO;
        BigDecimal secondSquares = BigDecimal.ZERO;
        for (int i = 0; i < first.returns().size(); i++) {
            BigDecimal firstDelta = first.returns().get(i).subtract(firstMean, MC);
            BigDecimal secondDelta = second.returns().get(i).subtract(secondMean, MC);
            covariance = covariance.add(firstDelta.multiply(secondDelta, MC), MC);
            firstSquares = firstSquares.add(firstDelta.multiply(firstDelta, MC), MC);
            secondSquares = secondSquares.add(secondDelta.multiply(secondDelta, MC), MC);
        }
        if (firstSquares.signum() <= 0 || secondSquares.signum() <= 0) {
            throw new IllegalArgumentException("return series variance collapsed at calculation precision");
        }
        BigDecimal denominator = firstSquares.multiply(secondSquares, MC).sqrt(MC);
        BigDecimal correlation = covariance.divide(denominator, MC)
                .max(BigDecimal.ONE.negate()).min(BigDecimal.ONE);
        return correlation.setScale(OUTPUT_SCALE, RoundingMode.HALF_EVEN)
                .stripTrailingZeros();
    }

    private void requireVariance(ReturnSeries series) {
        BigDecimal first = series.returns().get(0);
        if (series.returns().stream().allMatch(value -> value.compareTo(first) == 0)) {
            throw new IllegalArgumentException("return series has zero variance: " + series.componentId());
        }
    }
}
