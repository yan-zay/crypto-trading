package com.tj.crypto.admin.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record BacktestResultDTO(
        String id,
        String strategyName,
        String exchange,
        String marketType,
        String symbol,
        String timeframe,
        String startDate,
        String endDate,
        BigDecimal initialCapital,
        BigDecimal finalCapital,
        BigDecimal totalReturnPct,
        BigDecimal annualizedReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal winRatePct,
        BigDecimal sharpeRatio,
        BigDecimal sortinoRatio,
        BigDecimal calmarRatio,
        BigDecimal avgTradeDurationMs,
        int totalTrades,
        int signalCount,
        int winningTrades,
        int losingTrades,
        int maxWinStreak,
        int maxLoseStreak,
        BigDecimal avgWinPct,
        BigDecimal avgLossPct,
        BigDecimal profitFactor,
        BigDecimal totalFees,
        String strategyConfigJson,
        String assumptionsJson,
        Map<String, BigDecimal> monthlyReturnsPct,
        List<BacktestEquityPointDTO> equityCurve,
        List<BacktestSignalDTO> signals,
        List<BacktestTradeDTO> trades
) {}
