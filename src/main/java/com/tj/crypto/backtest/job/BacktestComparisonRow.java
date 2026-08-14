package com.tj.crypto.backtest.job;

import java.math.BigDecimal;

public record BacktestComparisonRow(
        int rank,
        String runId,
        String strategyName,
        String exchange,
        String marketType,
        String symbol,
        String timeframe,
        BigDecimal totalReturn,
        BigDecimal annualizedReturn,
        BigDecimal maxDrawdown,
        BigDecimal sharpe,
        BigDecimal sortino,
        BigDecimal calmar,
        BigDecimal winRate,
        BigDecimal profitFactor,
        BigDecimal totalFees,
        int totalTrades
) {}
