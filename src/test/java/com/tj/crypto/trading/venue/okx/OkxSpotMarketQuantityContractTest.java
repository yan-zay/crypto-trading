package com.tj.crypto.trading.venue.okx;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.trading.venue.VenueOrderCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OkxSpotMarketQuantityContractTest {

    @Test
    void spotMarketBuyExplicitlySizesInBaseCurrency() {
        Map<String, Object> body = new LinkedHashMap<>();

        OkxPrivateRestGateway.addSpotMarketQuantityUnit(body, command(MarketType.SPOT, OrderType.MARKET));

        assertThat(body).containsEntry("tgtCcy", "base_ccy");
    }

    @Test
    void limitAndDerivativeOrdersDoNotSendSpotMarketTargetCurrency() {
        Map<String, Object> limit = new LinkedHashMap<>();
        Map<String, Object> perpetual = new LinkedHashMap<>();

        OkxPrivateRestGateway.addSpotMarketQuantityUnit(limit, command(MarketType.SPOT, OrderType.LIMIT));
        OkxPrivateRestGateway.addSpotMarketQuantityUnit(
                perpetual, command(MarketType.PERPETUAL, OrderType.MARKET));

        assertThat(limit).doesNotContainKey("tgtCcy");
        assertThat(perpetual).doesNotContainKey("tgtCcy");
    }

    private VenueOrderCommand command(MarketType marketType, OrderType orderType) {
        Instrument instrument = new Instrument(Exchange.OKX, marketType, "BTCUSDT", "BTC", "USDT");
        return new VenueOrderCommand("account-1", "client-1", "strategy-1", instrument,
                TradeSide.BUY, OrderSide.LONG, orderType, new BigDecimal("0.1"),
                orderType == OrderType.LIMIT ? new BigDecimal("100") : null,
                false, 1, "CASH");
    }
}
