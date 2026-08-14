package com.tj.crypto.config.properties;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketUniversePropertiesTest {

    private final MarketUniverseProperties universe = new MarketUniverseProperties();

    @Test
    void acceptsThreePlatformsTwoMarketsAndBtcEth() {
        for (Exchange exchange : Exchange.values()) {
            assertThatCode(() -> universe.validate(exchange, MarketType.SPOT, "BTC-USDT"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> universe.validate(exchange, MarketType.PERPETUAL, "ETHUSDT"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsSymbolsAndDatedFuturesOutsideCurrentScope() {
        assertThatThrownBy(() -> universe.validate(
                Exchange.BINANCE, MarketType.SPOT, "SOLUSDT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed symbols");
        assertThatThrownBy(() -> universe.validate(
                Exchange.OKX, MarketType.FUTURES, "BTCUSDT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market type");
    }
}
