package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;

import java.math.BigDecimal;
import java.util.Locale;

/** Explicit live ticket. MARKET orders require a reference price for pre-trade controls. */
public record LiveOrderRequest(
        String accountId,
        String clientOrderId,
        String strategyId,
        Exchange exchange,
        MarketType marketType,
        String symbol,
        TradeSide side,
        OrderSide positionSide,
        OrderType orderType,
        BigDecimal quantity,
        BigDecimal limitPrice,
        BigDecimal referencePrice,
        int leverage,
        boolean reduceOnly,
        String marginMode,
        String correlationId
) {
    public LiveOrderRequest {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required for a live order");
        }
        if (exchange != Exchange.BINANCE && exchange != Exchange.OKX) {
            throw new IllegalArgumentException("Live orders support Binance and OKX only");
        }
        if (marketType != MarketType.SPOT && marketType != MarketType.PERPETUAL) {
            throw new IllegalArgumentException("Live orders support SPOT and PERPETUAL only");
        }
        if (symbol == null || symbol.isBlank() || side == null || orderType == null) {
            throw new IllegalArgumentException("symbol, side and orderType are required");
        }
        symbol = symbol.trim().toUpperCase(Locale.ROOT).replace("-", "");
        strategyId = strategyId == null || strategyId.isBlank() ? "MANUAL_LIVE" : strategyId.trim();
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (orderType == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("LIMIT order requires limitPrice");
        }
        if (orderType == OrderType.MARKET && (referencePrice == null || referencePrice.signum() <= 0)) {
            throw new IllegalArgumentException("MARKET order requires referencePrice for risk controls");
        }
        leverage = Math.max(1, leverage);
        marginMode = marginMode == null || marginMode.isBlank() ? "ISOLATED" : marginMode.toUpperCase();
        positionSide = positionSide == null ? (side == TradeSide.BUY ? OrderSide.LONG : OrderSide.SHORT) : positionSide;
    }
}
