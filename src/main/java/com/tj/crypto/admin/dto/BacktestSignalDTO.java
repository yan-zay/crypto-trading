package com.tj.crypto.admin.dto;

import java.math.BigDecimal;
import java.util.Map;

public record BacktestSignalDTO(
        long timestamp,
        String type,
        BigDecimal confidence,
        String reason,
        Map<String, BigDecimal> factorSnapshot
) {
}
