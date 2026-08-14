package com.tj.crypto.backtest.portfolio;

import java.math.BigDecimal;

/** 风控规则使用的一致账户快照。 */
public record AccountRiskSnapshot(
        BigDecimal equity,
        BigDecimal availableBalance,
        BigDecimal grossExposure,
        BigDecimal instrumentExposure
) {
}
