package com.tj.crypto.execution.journal;

import java.math.BigDecimal;

/** Additional venue/account facts attached to a single OMS fill. */
public record OmsFillMetadata(
        String accountId,
        String correlationId,
        String exchangeTradeId,
        BigDecimal fee,
        String feeCurrency,
        String liquidityRole,
        BigDecimal referencePrice,
        BigDecimal arrivalPrice,
        BigDecimal spreadBps,
        BigDecimal impactBps,
        BigDecimal slippageBps,
        int leverage,
        String marginMode
) {
    public static OmsFillMetadata empty() {
        return new OmsFillMetadata(null, null, null, BigDecimal.ZERO, null,
                null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                1, "ISOLATED");
    }
}
