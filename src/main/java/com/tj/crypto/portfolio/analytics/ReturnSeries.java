package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Ordered, aligned return observations for one strategy or instrument. */
public record ReturnSeries(
        String componentId,
        List<Long> observationTimesMs,
        List<BigDecimal> returns,
        String dataManifestSha256
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ReturnSeries {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId is required");
        }
        componentId = componentId.trim();
        if (observationTimesMs == null || returns == null || returns.size() < 2
                || observationTimesMs.size() != returns.size()) {
            throw new IllegalArgumentException("at least two return observations are required");
        }
        long previous = 0;
        for (Long time : observationTimesMs) {
            if (time == null || time <= 0 || time <= previous) {
                throw new IllegalArgumentException("observation times must be positive and strictly increasing");
            }
            previous = time;
        }
        if (returns.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("return observations must be non-null");
        }
        dataManifestSha256 = dataManifestSha256 == null ? null
                : dataManifestSha256.trim().toLowerCase(Locale.ROOT);
        if (dataManifestSha256 == null || !SHA_256.matcher(dataManifestSha256).matches()) {
            throw new IllegalArgumentException("dataManifestSha256 must be 64 lowercase hex characters");
        }
        observationTimesMs = List.copyOf(observationTimesMs);
        returns = List.copyOf(returns);
    }
}
