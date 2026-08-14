package com.tj.crypto.trading.paper;

import java.math.BigDecimal;

/** Aggregated post-trade attribution dimension. */
public record PaperAttribution(
        String dimension,
        String key,
        int trades,
        int wins,
        int losses,
        BigDecimal grossPnl,
        BigDecimal fees,
        BigDecimal funding,
        BigDecimal netPnl,
        BigDecimal winRatePct,
        BigDecimal avgTradePnl,
        BigDecimal profitFactor
) {}
