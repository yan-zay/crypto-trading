package com.tj.crypto.trading.paper;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;

import java.math.BigDecimal;
import java.util.Locale;

/** Explicit paper order command used by both the admin ticket and strategy adapter. */
public record PaperOrderRequest(
        String accountId,
        String clientOrderId,
        String strategyId,
        Exchange exchange,
        MarketType marketType,
        String symbol,
        TradeSide side,
        OrderType orderType,
        BigDecimal quantity,
        BigDecimal limitPrice,
        int leverage,
        boolean reduceOnly,
        String correlationId
) {
    public PaperOrderRequest {
        if (strategyId == null || strategyId.isBlank()) strategyId = "MANUAL";
        if (exchange == null || marketType == null || side == null || orderType == null) {
            throw new IllegalArgumentException("exchange, marketType, side and orderType are required");
        }
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        symbol = symbol.trim().toUpperCase(Locale.ROOT).replace("-", "");
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (orderType == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("limitPrice is required for LIMIT orders");
        }
        if (leverage < 1) leverage = 1;
    }
}
