package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;

import java.math.BigDecimal;

/** Venue-neutral private order command. Price is the limit price or the governed MARKET valuation reference. */
public record VenueOrderCommand(
        String accountId,
        String clientOrderId,
        String strategyId,
        Instrument instrument,
        TradeSide tradeSide,
        OrderSide positionSide,
        OrderType orderType,
        BigDecimal quantity,
        BigDecimal price,
        boolean reduceOnly,
        int leverage,
        String marginMode
) {
    /** Compatibility constructor for deterministic risk-unit tests. Live dispatch uses a release-bound strategy id. */
    public VenueOrderCommand(String accountId, String clientOrderId, Instrument instrument,
                             TradeSide tradeSide, OrderSide positionSide, OrderType orderType,
                             BigDecimal quantity, BigDecimal price, boolean reduceOnly,
                             int leverage, String marginMode) {
        this(accountId, clientOrderId, "UNBOUND", instrument, tradeSide, positionSide,
                orderType, quantity, price, reduceOnly, leverage, marginMode);
    }

    public VenueOrderCommand {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("accountId is required");
        if (clientOrderId == null || clientOrderId.isBlank()) throw new IllegalArgumentException("clientOrderId is required");
        if (strategyId == null || strategyId.isBlank()) throw new IllegalArgumentException("strategyId is required");
        accountId = accountId.trim();
        clientOrderId = clientOrderId.trim();
        strategyId = strategyId.trim();
        if (instrument == null || tradeSide == null || orderType == null) throw new IllegalArgumentException("instrument, side and type are required");
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (orderType == OrderType.LIMIT && (price == null || price.signum() <= 0)) {
            throw new IllegalArgumentException("LIMIT order requires a positive price");
        }
        leverage = Math.max(1, leverage);
        marginMode = marginMode == null || marginMode.isBlank() ? "ISOLATED" : marginMode.toUpperCase();
    }
}
