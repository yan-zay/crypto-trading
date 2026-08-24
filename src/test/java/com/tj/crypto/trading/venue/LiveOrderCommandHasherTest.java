package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LiveOrderCommandHasherTest {

    @Test
    void marketValuationReferenceIsPartOfTheGovernedCommandDigest() {
        VenueOrderCommand first = market("100");
        VenueOrderCommand changedReference = market("101");

        assertThat(LiveOrderCommandHasher.hash(first))
                .isNotEqualTo(LiveOrderCommandHasher.hash(changedReference));
    }

    private VenueOrderCommand market(String referencePrice) {
        Instrument instrument = new Instrument(Exchange.BINANCE, MarketType.PERPETUAL,
                "BTCUSDT", "BTC", "USDT");
        return new VenueOrderCommand("account-1", "client-1", "strategy-1", instrument,
                TradeSide.BUY, OrderSide.LONG, OrderType.MARKET, BigDecimal.ONE,
                new BigDecimal(referencePrice), false, 1, "ISOLATED");
    }
}
