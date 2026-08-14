package com.tj.crypto.backtest.robustness;

/** Zero-based half-open observation range. */
public record IndexRange(int startInclusive, int endExclusive) {

    public IndexRange {
        if (startInclusive < 0) {
            throw new IllegalArgumentException("startInclusive must be non-negative");
        }
        if (endExclusive < startInclusive) {
            throw new IllegalArgumentException("endExclusive must be greater than or equal to startInclusive");
        }
    }

    public int length() {
        return endExclusive - startInclusive;
    }

    public boolean contains(int index) {
        return index >= startInclusive && index < endExclusive;
    }
}
