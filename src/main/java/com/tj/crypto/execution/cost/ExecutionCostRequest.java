package com.tj.crypto.execution.cost;

import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;

import java.math.BigDecimal;

/** Inputs available to a bar-based execution simulator. */
public record ExecutionCostRequest(
        TradeSide side,
        OrderType orderType,
        BigDecimal requestedQuantity,
        BigDecimal referencePrice,
        BigDecimal limitPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal baseVolume,
        long latencyMs
) {
    public ExecutionCostRequest {
        if (side == null || orderType == null) throw new IllegalArgumentException("side and orderType are required");
        requirePositive(requestedQuantity, "requestedQuantity");
        requirePositive(referencePrice, "referencePrice");
        highPrice = highPrice == null ? referencePrice : highPrice;
        lowPrice = lowPrice == null ? referencePrice : lowPrice;
        baseVolume = baseVolume == null ? BigDecimal.ZERO : baseVolume.max(BigDecimal.ZERO);
        if (latencyMs < 0) throw new IllegalArgumentException("latencyMs must not be negative");
        if (orderType == OrderType.LIMIT) requirePositive(limitPrice, "limitPrice");
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
