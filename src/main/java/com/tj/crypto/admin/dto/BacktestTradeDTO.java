package com.tj.crypto.admin.dto;

import java.math.BigDecimal;

public record BacktestTradeDTO(
        long entryTime,
        long exitTime,
        String side,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal quantity,
        BigDecimal pnl,
        BigDecimal pnlPct,
        BigDecimal fees
) {}
