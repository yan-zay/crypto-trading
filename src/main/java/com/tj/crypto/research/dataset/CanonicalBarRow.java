package com.tj.crypto.research.dataset;

import java.math.BigDecimal;

/** One checksum-bound finalized bar from the canonical research CSV schema. */
public record CanonicalBarRow(
        long timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal quoteVolume
) {}
