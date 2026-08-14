package com.tj.crypto.trading.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.config.properties.BinanceHistoricalDataProperties;
import com.tj.crypto.trading.venue.stream.EventFingerprint;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BinanceInstrumentRuleProvider implements InstrumentRuleProvider {
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final BinanceHistoricalDataProperties properties;

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public List<InstrumentRuleSnapshot> fetch() {
        List<InstrumentRuleSnapshot> result = new ArrayList<>();
        for (String symbol : List.of("BTCUSDT", "ETHUSDT")) {
            result.add(fetchOne(properties.getSpotBaseUrl(), "/api/v3/exchangeInfo", symbol, MarketType.SPOT));
            result.add(fetchOne(properties.getPerpetualBaseUrl(), "/fapi/v1/exchangeInfo", symbol, MarketType.PERPETUAL));
        }
        return result;
    }

    private InstrumentRuleSnapshot fetchOne(String baseUrl, String path, String symbol,
                                            MarketType marketType) {
        String body = get(baseUrl + path + "?symbol=" + symbol);
        try {
            JsonNode item = objectMapper.readTree(body).path("symbols").get(0);
            if (item == null) throw new IllegalStateException("Binance instrument missing: " + symbol);
            JsonNode price = filter(item, "PRICE_FILTER");
            JsonNode lot = filter(item, "LOT_SIZE");
            JsonNode notional = filter(item, "NOTIONAL");
            if (notional == null) notional = filter(item, "MIN_NOTIONAL");
            String base = item.path("baseAsset").asText();
            String quote = item.path("quoteAsset").asText();
            return new InstrumentRuleSnapshot(exchange(), marketType, symbol, symbol, base, quote,
                    marketType == MarketType.SPOT ? quote : item.path("marginAsset").asText(quote),
                    "TRADING".equals(item.path("status").asText()) ? "TRADING" : "SUSPENDED",
                    decimal(price, "tickSize"), decimal(lot, "stepSize"), decimal(lot, "minQty"),
                    decimal(lot, "maxQty"), decimalOrNull(notional, "minNotional"), BigDecimal.ONE,
                    "BINANCE:" + EventFingerprint.sha256(body).substring(0, 20));
        } catch (IOException e) {
            throw new IllegalStateException("Invalid Binance exchangeInfo", e);
        }
    }

    private JsonNode filter(JsonNode item, String type) {
        for (JsonNode filter : item.path("filters")) {
            if (type.equals(filter.path("filterType").asText())) return filter;
        }
        return null;
    }

    private String get(String url) {
        try (Response response = httpClient.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Binance exchangeInfo HTTP " + response.code());
            }
            return response.body().string();
        } catch (IOException e) {
            throw new IllegalStateException("Binance exchangeInfo transport failed", e);
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        BigDecimal value = decimalOrNull(node, field);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null) return null;
        String value = node.path(field).asText();
        return value.isBlank() ? null : new BigDecimal(value);
    }
}
