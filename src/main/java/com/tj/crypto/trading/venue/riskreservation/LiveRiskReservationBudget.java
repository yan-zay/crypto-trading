package com.tj.crypto.trading.venue.riskreservation;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;

import java.math.BigDecimal;

/**
 * Absolute gross-notional limits derived by the deterministic venue pre-trade validator.
 *
 * <p>Current exposure comes from the signed venue snapshot. Durable non-terminal order
 * reservations are added under a database lock by {@link LiveRiskReservationService}.</p>
 */
public record LiveRiskReservationBudget(
        String accountId,
        Exchange exchange,
        MarketType marketType,
        String symbol,
        BigDecimal originalQuantity,
        BigDecimal referencePrice,
        BigDecimal orderNotional,
        BigDecimal currentSymbolGrossNotional,
        BigDecimal currentPortfolioGrossNotional,
        BigDecimal maxSingleOrderNotional,
        BigDecimal maxSymbolGrossNotional,
        BigDecimal maxPortfolioGrossNotional,
        boolean riskIncreasing,
        long snapshotEventTimeMs
) {
    public static LiveRiskReservationBudget reduction(
            String accountId, Exchange exchange, MarketType marketType, String symbol,
            BigDecimal quantity, BigDecimal referencePrice, long snapshotEventTimeMs) {
        return new LiveRiskReservationBudget(accountId, exchange, marketType, symbol,
                quantity, referencePrice, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, snapshotEventTimeMs);
    }
}
