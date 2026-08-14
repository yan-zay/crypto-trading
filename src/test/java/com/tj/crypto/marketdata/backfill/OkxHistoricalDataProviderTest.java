package com.tj.crypto.marketdata.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.OkxProperties;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.normalize.OkxKlineNormalizer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OkxHistoricalDataProviderTest {

    private OkxHistoricalDataProvider provider;

    @BeforeEach
    void setUp() {
        OkxProperties properties = new OkxProperties();
        properties.setRestBaseUrl("https://www.okx.com");
        provider = new OkxHistoricalDataProvider(
                new OkHttpClient(), properties, new ObjectMapper(), new OkxKlineNormalizer());
    }

    @Test
    void buildsHistoryCandlesCursorUrl() {
        String url = provider.buildUrl("BTC-USDT-SWAP", Timeframe.H4, 1700000000000L);

        assertThat(url).startsWith("https://www.okx.com/api/v5/market/history-candles?");
        assertThat(url).contains("instId=BTC-USDT-SWAP");
        assertThat(url).contains("bar=4H");
        assertThat(url).contains("after=1700000000000");
        assertThat(url).contains("limit=300");
    }

    @Test
    void parsesAndSortsNewestFirstResponse() {
        String json = """
                {"code":"0","msg":"","data":[
                  ["1700000060000","105","111","101","108","3","300","324","1"],
                  ["1700000000000","100","110","90","105","2","200","210","1"]
                ]}
                """;

        List<BarEvent> bars = provider.parseResponse(json, "BTC-USDT-SWAP", "candle1m");

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).metadata().exchangeTimestamp()).isEqualTo(1700000000000L);
        assertThat(bars.get(1).metadata().exchangeTimestamp()).isEqualTo(1700000060000L);
    }

    @Test
    void rejectsNonOkxInstrumentBeforeNetworkCall() {
        Instrument binance = Instrument.of(
                Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> provider.loadBars(binance, Timeframe.M1, 1L, 2L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesUpstreamProtocolErrors() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                provider.parseResponse(
                        "{\"code\":\"50011\",\"msg\":\"Rate limit\",\"data\":[]}",
                        "BTC-USDT-SWAP", "candle1m"))
                .isInstanceOf(HistoricalDataAccessException.class)
                .hasMessageContaining("50011");
    }
}
