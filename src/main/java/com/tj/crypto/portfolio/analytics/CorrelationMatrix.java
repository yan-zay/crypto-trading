package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable Pearson correlation matrix with a deterministic component order. */
public record CorrelationMatrix(
        List<String> componentIds,
        List<List<BigDecimal>> values,
        int observationCount,
        List<Long> observationTimesMs,
        Map<String, String> sourceManifestSha256ByComponent
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final double PSD_TOLERANCE = 1e-10;

    public CorrelationMatrix {
        componentIds = List.copyOf(componentIds);
        if (componentIds.isEmpty() || observationCount < 2 || values.size() != componentIds.size()) {
            throw new IllegalArgumentException("invalid correlation matrix dimensions");
        }
        observationTimesMs = List.copyOf(observationTimesMs);
        if (observationTimesMs.size() != observationCount) {
            throw new IllegalArgumentException("observation times must match observationCount");
        }
        long previous = 0;
        for (Long time : observationTimesMs) {
            if (time == null || time <= 0 || time <= previous) {
                throw new IllegalArgumentException("observation times must be strictly increasing");
            }
            previous = time;
        }
        if (sourceManifestSha256ByComponent == null
                || !sourceManifestSha256ByComponent.keySet().equals(new HashSet<>(componentIds))) {
            throw new IllegalArgumentException("source manifests must exactly match correlation components");
        }
        LinkedHashMap<String, String> manifests = new LinkedHashMap<>();
        for (String componentId : componentIds) {
            String hash = sourceManifestSha256ByComponent.get(componentId);
            hash = hash == null ? null : hash.trim().toLowerCase(Locale.ROOT);
            if (hash == null || !SHA_256.matcher(hash).matches()) {
                throw new IllegalArgumentException("invalid source manifest for " + componentId);
            }
            manifests.put(componentId, hash);
        }
        sourceManifestSha256ByComponent = Collections.unmodifiableMap(manifests);
        if (componentIds.stream().anyMatch(id -> id == null || id.isBlank())
                || new HashSet<>(componentIds).size() != componentIds.size()) {
            throw new IllegalArgumentException("correlation component ids must be unique and non-blank");
        }
        List<List<BigDecimal>> copy = new ArrayList<>();
        for (List<BigDecimal> row : values) {
            if (row == null || row.size() != componentIds.size() || row.contains(null)) {
                throw new IllegalArgumentException("correlation matrix must be square and complete");
            }
            copy.add(List.copyOf(row));
        }
        for (int row = 0; row < copy.size(); row++) {
            for (int column = 0; column < copy.size(); column++) {
                BigDecimal value = copy.get(row).get(column);
                if (value.compareTo(BigDecimal.ONE.negate()) < 0
                        || value.compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("correlation values must lie in [-1, 1]");
                }
                if (row == column && value.compareTo(BigDecimal.ONE) != 0) {
                    throw new IllegalArgumentException("correlation diagonal must equal 1");
                }
                if (value.compareTo(copy.get(column).get(row)) != 0) {
                    throw new IllegalArgumentException("correlation matrix must be symmetric");
                }
            }
        }
        requirePositiveSemidefinite(copy);
        values = Collections.unmodifiableList(copy);
    }

    private static void requirePositiveSemidefinite(List<List<BigDecimal>> matrix) {
        int size = matrix.size();
        double[][] lower = new double[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column <= row; column++) {
                double remainder = matrix.get(row).get(column).doubleValue();
                for (int k = 0; k < column; k++) remainder -= lower[row][k] * lower[column][k];
                if (row == column) {
                    if (remainder < -PSD_TOLERANCE) {
                        throw new IllegalArgumentException("correlation matrix must be positive semidefinite");
                    }
                    lower[row][column] = StrictMath.sqrt(StrictMath.max(0.0d, remainder));
                } else if (lower[column][column] > PSD_TOLERANCE) {
                    lower[row][column] = remainder / lower[column][column];
                } else if (StrictMath.abs(remainder) > PSD_TOLERANCE) {
                    throw new IllegalArgumentException("correlation matrix must be positive semidefinite");
                }
            }
        }
    }

    public BigDecimal value(String firstComponentId, String secondComponentId) {
        int first = componentIds.indexOf(firstComponentId);
        int second = componentIds.indexOf(secondComponentId);
        if (first < 0 || second < 0) {
            throw new IllegalArgumentException("unknown correlation component");
        }
        return values.get(first).get(second);
    }

    public Map<String, BigDecimal> row(String componentId) {
        int index = componentIds.indexOf(componentId);
        if (index < 0) throw new IllegalArgumentException("unknown correlation component: " + componentId);
        Map<String, BigDecimal> row = new LinkedHashMap<>();
        for (int i = 0; i < componentIds.size(); i++) row.put(componentIds.get(i), values.get(index).get(i));
        return Collections.unmodifiableMap(row);
    }
}
