package com.tj.crypto.marketdata.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OkxKlineNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkxKlineNormalizer normalizer = new OkxKlineNormalizer();

    @Test
    void normalizesPerpetualClosedCandleAndQuoteVolume() throws Exception {
        JsonNode candle = mapper.readTree("""
                ["1700000000000","37000.1","37100.2","36900.3","37050.4",
                 "12.5","12500","462500.75","1"]
                """);

        BarEvent bar = normalizer.normalize(
                "BTC-USDT-SWAP", "candle1m", candle, 1700000000123L);

        assertThat(bar).isNotNull();
        assertThat(bar.instrument().exchange()).isEqualTo(Exchange.OKX);
        assertThat(bar.instrument().marketType()).isEqualTo(MarketType.PERPETUAL);
        assertThat(bar.instrument().symbol()).isEqualTo("BTCUSDT");
        assertThat(bar.timeframe()).isEqualTo(Timeframe.M1);
        // For derivatives, OKX index 5 is contract count and index 6 is base-asset volume.
        assertThat(bar.volume()).isEqualByComparingTo(new BigDecimal("12500"));
        assertThat(bar.quoteVolume()).isEqualByComparingTo(new BigDecimal("462500.75"));
        assertThat(bar.closed()).isTrue();
        assertThat(bar.metadata().receivedTimestamp()).isEqualTo(1700000000123L);
    }

    @Test
    void keepsUnconfirmedCandleAsForming() throws Exception {
        JsonNode candle = mapper.readTree(
                "[\"1700000000000\",\"1\",\"2\",\"0.5\",\"1.5\",\"10\",\"10\",\"15\",\"0\"]");

        BarEvent bar = normalizer.normalize("ETH-USDT", "candle1H", candle, 1L);

        assertThat(bar).isNotNull();
        assertThat(bar.instrument().marketType()).isEqualTo(MarketType.SPOT);
        assertThat(bar.volume()).isEqualByComparingTo("10");
        assertThat(bar.closed()).isFalse();
    }
}
