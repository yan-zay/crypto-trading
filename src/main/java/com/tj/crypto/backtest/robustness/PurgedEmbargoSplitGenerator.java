package com.tj.crypto.backtest.robustness;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates deterministic contiguous validation folds with symmetric purging and a
 * post-validation embargo.
 *
 * <p>This prevents direct adjacency leakage only. The caller remains responsible for choosing
 * purge/embargo lengths that cover the actual label horizon and feature lookback.</p>
 */
public final class PurgedEmbargoSplitGenerator {

    public List<PurgedEmbargoSplit> generate(
            int observationCount,
            int foldCount,
            int purgeSize,
            int embargoSize) {
        validate(observationCount, foldCount, purgeSize, embargoSize);
        List<PurgedEmbargoSplit> splits = new ArrayList<>(foldCount);

        for (int fold = 0; fold < foldCount; fold++) {
            int validationStart = fold * observationCount / foldCount;
            int validationEnd = (fold + 1) * observationCount / foldCount;
            IndexRange validation = new IndexRange(validationStart, validationEnd);

            int purgeBeforeStart = Math.max(0, validationStart - purgeSize);
            int purgeAfterEnd = Math.min(observationCount, validationEnd + purgeSize);
            int embargoEnd = Math.min(observationCount, purgeAfterEnd + embargoSize);

            List<IndexRange> training = new ArrayList<>(2);
            addNonEmpty(training, 0, purgeBeforeStart);
            addNonEmpty(training, embargoEnd, observationCount);
            if (training.isEmpty()) {
                throw new IllegalArgumentException(
                        "purgeSize and embargoSize leave no training observations for fold " + fold);
            }

            List<IndexRange> purged = new ArrayList<>(2);
            addNonEmpty(purged, purgeBeforeStart, validationStart);
            addNonEmpty(purged, validationEnd, purgeAfterEnd);

            splits.add(new PurgedEmbargoSplit(
                    fold,
                    observationCount,
                    purgeSize,
                    embargoSize,
                    validation,
                    training,
                    purged,
                    new IndexRange(purgeAfterEnd, embargoEnd)));
        }
        return List.copyOf(splits);
    }

    private void validate(int observationCount, int foldCount, int purgeSize, int embargoSize) {
        if (observationCount < 2) {
            throw new IllegalArgumentException("observationCount must be at least 2");
        }
        if (foldCount < 2 || foldCount > observationCount) {
            throw new IllegalArgumentException("foldCount must be between 2 and observationCount");
        }
        if (purgeSize < 0) throw new IllegalArgumentException("purgeSize must be non-negative");
        if (embargoSize < 0) throw new IllegalArgumentException("embargoSize must be non-negative");
    }

    private void addNonEmpty(List<IndexRange> ranges, int start, int end) {
        if (end > start) ranges.add(new IndexRange(start, end));
    }
}
