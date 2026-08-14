package com.tj.crypto.backtest.robustness;

import java.util.List;
import java.util.Objects;

/**
 * One auditable time-series validation split.
 *
 * <p>Purging excludes observations adjacent to the validation range. Embargo is applied after
 * the post-validation purge. Ranges are zero-based and half-open.</p>
 */
public record PurgedEmbargoSplit(
        int foldIndex,
        int observationCount,
        int purgeSize,
        int embargoSize,
        IndexRange validationRange,
        List<IndexRange> trainingRanges,
        List<IndexRange> purgedRanges,
        IndexRange embargoRange
) {
    public PurgedEmbargoSplit {
        if (foldIndex < 0) throw new IllegalArgumentException("foldIndex must be non-negative");
        if (observationCount < 2) throw new IllegalArgumentException("observationCount must be at least 2");
        if (purgeSize < 0) throw new IllegalArgumentException("purgeSize must be non-negative");
        if (embargoSize < 0) throw new IllegalArgumentException("embargoSize must be non-negative");
        Objects.requireNonNull(validationRange, "validationRange");
        Objects.requireNonNull(trainingRanges, "trainingRanges");
        Objects.requireNonNull(purgedRanges, "purgedRanges");
        Objects.requireNonNull(embargoRange, "embargoRange");
        trainingRanges = List.copyOf(trainingRanges);
        purgedRanges = List.copyOf(purgedRanges);
        if (validationRange.length() == 0) {
            throw new IllegalArgumentException("validationRange must not be empty");
        }
        if (trainingRanges.isEmpty() || trainingRanges.stream().anyMatch(range -> range.length() == 0)) {
            throw new IllegalArgumentException("trainingRanges must contain at least one non-empty range");
        }
        if (validationRange.endExclusive() > observationCount
                || embargoRange.endExclusive() > observationCount
                || trainingRanges.stream().anyMatch(range -> range.endExclusive() > observationCount)
                || purgedRanges.stream().anyMatch(range -> range.endExclusive() > observationCount)) {
            throw new IllegalArgumentException("all ranges must be inside observationCount");
        }
    }
}
