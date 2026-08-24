package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalMarketFactValidationTest {
    private final Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    private final EventMetadata metadata = new EventMetadata(Exchange.BINANCE, 1_000, 1_100, null);

    @Test
    void fundingAndOpenInterestRejectImpossibleOrUntraceableFacts() {
        assertThatThrownBy(() -> new FundingRateEvent(instrument, metadata, null, null, 2_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundingRateEvent(instrument, metadata, new BigDecimal("1.01"), null, 2_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenInterestEvent(instrument, metadata,
                new BigDecimal("-1"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexPriceEvent(instrument,
                new EventMetadata(Exchange.OKX, 1_000, 1_100, null), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
