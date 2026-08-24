package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;

import java.math.BigDecimal;

/** 独立的标记价格观测；不与异步到达的指数价格做隐式 join。 */
public record MarkPriceEvent(
        Instrument instrument,
        EventMetadata metadata,
        BigDecimal markPrice,
        BigDecimal estimatedSettlementPrice
) implements MarketEvent {
    public MarkPriceEvent {
        MarketEventValidation.requireIdentity(instrument, metadata);
        positive(markPrice, "markPrice");
        if (estimatedSettlementPrice != null && estimatedSettlementPrice.signum() <= 0) {
            throw new IllegalArgumentException("estimatedSettlementPrice must be positive when present");
        }
    }

    private static void positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
