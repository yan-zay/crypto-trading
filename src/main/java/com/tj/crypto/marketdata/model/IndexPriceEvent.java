package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;

import java.math.BigDecimal;

/** 独立的指数价格观测；保留其自身交易所时间，避免陈旧 mark/index 拼接。 */
public record IndexPriceEvent(
        Instrument instrument,
        EventMetadata metadata,
        BigDecimal indexPrice
) implements MarketEvent {
    public IndexPriceEvent {
        MarketEventValidation.requireIdentity(instrument, metadata);
        if (indexPrice == null || indexPrice.signum() <= 0) {
            throw new IllegalArgumentException("indexPrice must be positive");
        }
    }
}
