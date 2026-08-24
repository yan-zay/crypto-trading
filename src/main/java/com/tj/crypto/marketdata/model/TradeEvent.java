package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.TradeSide;

import java.math.BigDecimal;

/** 标准化逐笔成交事件。 */
public record TradeEvent(
        Instrument instrument,
        EventMetadata metadata,
        String tradeId,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteQuantity,
        TradeSide aggressorSide
) implements MarketEvent {
    public TradeEvent {
        MarketEventValidation.requireIdentity(instrument, metadata);
        tradeId = required(tradeId, "tradeId");
        positive(price, "price");
        positive(quantity, "quantity");
        positive(quoteQuantity, "quoteQuantity");
        if (aggressorSide == null) throw new IllegalArgumentException("aggressorSide is required");
    }

    public TradeEvent(Instrument instrument, EventMetadata metadata, String tradeId,
                      BigDecimal price, BigDecimal quantity, TradeSide aggressorSide) {
        this(instrument, metadata, tradeId, price, quantity, price.multiply(quantity), aggressorSide);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static void positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
