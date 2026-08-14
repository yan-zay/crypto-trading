package com.tj.crypto.marketdata.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.CoinglassProperties;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.normalize.CoinglassKlineNormalizer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoinglassHistoricalDataProviderTest {

    private CoinglassHistoricalDataProvider provider;

    @BeforeEach
    void setUp() {
        CoinglassProperties properties = new CoinglassProperties();
        properties.setApiKey("test-key");
        properties.setRestBaseUrl("https://open-api-v4.coinglass.com");
        properties.setPriceExchange("Binance");
        provider = new CoinglassHistoricalDataProvider(
                new OkHttpClient(), properties, new ObjectMapper(),
                new CoinglassKlineNormalizer());
    }

    @Test
    void buildsOfficialSpotAndFuturesUrls() {
        Instrument spot = Instrument.of(Exchange.COINGLASS, MarketType.SPOT, "BTCUSDT");
        Instrument perpetual = Instrument.of(
                Exchange.COINGLASS, MarketType.PERPETUAL, "ETHUSDT");

        assertThat(provider.buildUrl(spot, Timeframe.M5, 100L, 200L))
                .startsWith("https://open-api-v4.coinglass.com/api/spot/price/history?")
                .contains("exchange=Binance", "symbol=BTCUSDT", "interval=5m",
                        "limit=1000", "start_time=100", "end_time=200");
        assertThat(provider.buildUrl(perpetual, Timeframe.H1, 100L, 200L))
                .startsWith("https://open-api-v4.coinglass.com/api/futures/price/history?")
                .contains("symbol=ETHUSDT", "interval=1h");
    }

    @Test
    void parsesSpotOhlcAndKeepsUsdVolumeAsQuoteVolume() {
        Instrument spot = Instrument.of(Exchange.COINGLASS, MarketType.SPOT, "BTCUSDT");
        List<BarEvent> bars = provider.parseResponse("""
                {"code":"0","msg":"success","data":[
                  {"time":1700000060000,"open":105,"high":112,"low":101,"close":108,"volume_usd":324},
                  {"time":1700000000000,"open":"100","high":"110","low":"90","close":"105","volume_usd":"210"}
                ]}
                """, spot, Timeframe.M1, 1700000100000L);

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).metadata().exchangeTimestamp()).isEqualTo(1700000000000L);
        assertThat(bars.get(0).instrument().exchange()).isEqualTo(Exchange.COINGLASS);
        assertThat(bars.get(0).instrument().marketType()).isEqualTo(MarketType.SPOT);
        assertThat(bars.get(0).volume()).isZero();
        assertThat(bars.get(0).quoteVolume()).isEqualByComparingTo("210");
        assertThat(bars.get(0).closed()).isTrue();
    }

    @Test
    void rejectsUnsupportedExchange() {
        Instrument binance = Instrument.of(
                Exchange.BINANCE, MarketType.SPOT, "BTCUSDT");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                provider.buildUrl(binance, Timeframe.M1, 1L, 2L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesUpstreamProtocolErrors() {
        Instrument spot = Instrument.of(
                Exchange.COINGLASS, MarketType.SPOT, "BTCUSDT");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.parseResponse(
                        "{\"code\":\"40001\",\"msg\":\"Rate limit\",\"data\":[]}",
                        spot, Timeframe.M1, 100L))
                .isInstanceOf(HistoricalDataAccessException.class)
                .hasMessageContaining("40001");
    }
}
