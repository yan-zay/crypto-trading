package com.tj.crypto.trading.instrument;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Time-versioned exchange rules used by order validation and account valuation. */
public record InstrumentMetadata(
        long metadataId,
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String venueSymbol,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        String status,
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        BigDecimal minNotional,
        BigDecimal contractMultiplier,
        BigDecimal makerFeeRate,
        BigDecimal takerFeeRate,
        int maxLeverage,
        BigDecimal maintenanceMarginRate,
        Integer fundingIntervalHours,
        long validFromMs,
        Long validToMs,
        String sourceVersion
) {
    public Instrument instrument() {
        return new Instrument(exchange, marketType, symbol, baseAsset, quoteAsset);
    }

    public BigDecimal alignPrice(BigDecimal price) {
        requirePositive(price, "price");
        return align(price, tickSize, RoundingMode.HALF_UP);
    }

    public BigDecimal alignQuantity(BigDecimal quantity) {
        requirePositive(quantity, "quantity");
        return align(quantity, stepSize, RoundingMode.FLOOR);
    }

    public void validate(BigDecimal price, BigDecimal quantity, int leverage) {
        requirePositive(price, "price");
        requirePositive(quantity, "quantity");
        if (!"TRADING".equals(status)) {
            throw new IllegalArgumentException("Instrument is not tradable: " + status);
        }
        if (quantity.compareTo(minQuantity) < 0) {
            throw new IllegalArgumentException("Quantity is below instrument minimum: " + minQuantity);
        }
        if (maxQuantity.signum() > 0 && quantity.compareTo(maxQuantity) > 0) {
            throw new IllegalArgumentException("Quantity exceeds instrument maximum: " + maxQuantity);
        }
        BigDecimal notional = quantity.multiply(price).multiply(contractMultiplier);
        if (notional.compareTo(minNotional) < 0) {
            throw new IllegalArgumentException("Notional is below instrument minimum: " + minNotional);
        }
        if (leverage < 1 || leverage > maxLeverage) {
            throw new IllegalArgumentException("Leverage must be between 1 and " + maxLeverage);
        }
    }

    public BigDecimal feeRate(boolean maker) {
        return maker ? makerFeeRate : takerFeeRate;
    }

    private BigDecimal align(BigDecimal value, BigDecimal increment, RoundingMode mode) {
        if (increment == null || increment.signum() <= 0) return value;
        return value.divide(increment, 0, mode).multiply(increment).stripTrailingZeros();
    }

    private void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
