package com.tj.crypto.trading.venue.stream;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.venue.VenueOrderState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenueFillPriceTruthTest {

    @Test
    void missingVenueFillPriceCannotFallBackToOrderReferencePrice() {
        assertThatThrownBy(() -> VenuePrivateEventProcessor.requireVenueFillPrice(update(null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative fill price");
    }

    @Test
    void explicitLastOrAverageFillPriceIsAccepted() {
        assertThat(VenuePrivateEventProcessor.requireVenueFillPrice(update("101", "100")))
                .isEqualByComparingTo("101");
        assertThat(VenuePrivateEventProcessor.requireVenueFillPrice(update(null, "100")))
                .isEqualByComparingTo("100");
    }

    private VenueOrderUpdate update(String last, String average) {
        Instrument instrument = new Instrument(Exchange.BINANCE, MarketType.PERPETUAL,
                "BTCUSDT", "BTC", "USDT");
        return new VenueOrderUpdate(Exchange.BINANCE, "event-1", "a".repeat(64), 1,
                instrument, "venue-1", "client-1", "trade-1", "FILLED",
                VenueOrderState.FILLED, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                decimal(last), decimal(average), BigDecimal.ZERO, "USDT", "TAKER");
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
