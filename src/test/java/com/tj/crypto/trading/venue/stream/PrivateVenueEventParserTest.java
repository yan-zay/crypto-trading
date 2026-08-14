package com.tj.crypto.trading.venue.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.venue.VenueOrderState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateVenueEventParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesBinanceSpotExecutionReport() {
        String payload = """
                {"e":"executionReport","E":1710000000000,"s":"BTCUSDT","c":"client-1",
                 "i":123,"t":456,"X":"PARTIALLY_FILLED","q":"1.0","z":"0.2",
                 "l":"0.2","L":"60000","Z":"12000","n":"1.2","N":"USDT","m":false}
                """;
        VenueOrderUpdate update = (VenueOrderUpdate) new BinancePrivateEventParser(objectMapper)
                .parse(payload, MarketType.SPOT).get(0);
        assertThat(update.state()).isEqualTo(VenueOrderState.PARTIALLY_FILLED);
        assertThat(update.cumulativeFilledQuantity()).isEqualByComparingTo("0.2");
        assertThat(update.averageFillPrice()).isEqualByComparingTo("60000");
        assertThat(update.liquidityRole()).isEqualTo("TAKER");
    }

    @Test
    void parsesBinanceFuturesOrderUpdate() {
        String payload = """
                {"e":"ORDER_TRADE_UPDATE","E":1710000001000,"o":{"s":"ETHUSDT","c":"c2",
                 "i":124,"t":457,"X":"FILLED","q":"2","z":"2","l":"2","L":"3000",
                 "ap":"3000","n":"2.4","N":"USDT","m":true}}
                """;
        VenueOrderUpdate update = (VenueOrderUpdate) new BinancePrivateEventParser(objectMapper)
                .parse(payload, MarketType.PERPETUAL).get(0);
        assertThat(update.instrument().marketType()).isEqualTo(MarketType.PERPETUAL);
        assertThat(update.state()).isEqualTo(VenueOrderState.FILLED);
        assertThat(update.liquidityRole()).isEqualTo("MAKER");
    }

    @Test
    void parsesOkxSwapOrderUpdate() {
        String payload = """
                {"arg":{"channel":"orders","instType":"SWAP"},"data":[{
                 "instId":"BTC-USDT-SWAP","ordId":"99","clOrdId":"client-9","tradeId":"777",
                 "state":"partially_filled","sz":"1","accFillSz":"0.25","fillSz":"0.25",
                 "fillPx":"61000","avgPx":"61000","fee":"-1.1","feeCcy":"USDT",
                 "execType":"T","uTime":"1710000002000"}]}
                """;
        VenueOrderUpdate update = (VenueOrderUpdate) new OkxPrivateEventParser(objectMapper)
                .parse(payload, MarketType.SPOT).get(0);
        assertThat(update.instrument().symbol()).isEqualTo("BTCUSDT");
        assertThat(update.instrument().marketType()).isEqualTo(MarketType.PERPETUAL);
        assertThat(update.fee()).isEqualByComparingTo(new BigDecimal("1.1"));
    }
}
