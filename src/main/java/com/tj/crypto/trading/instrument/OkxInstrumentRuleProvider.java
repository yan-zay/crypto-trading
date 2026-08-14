package com.tj.crypto.trading.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.config.properties.OkxProperties;
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
public class OkxInstrumentRuleProvider implements InstrumentRuleProvider {
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OkxProperties properties;

    @Override
    public Exchange exchange() {
        return Exchange.OKX;
    }

    @Override
    public List<InstrumentRuleSnapshot> fetch() {
        List<InstrumentRuleSnapshot> result = new ArrayList<>();
        for (String base : List.of("BTC", "ETH")) {
            result.add(fetchOne(base + "-USDT", MarketType.SPOT));
            result.add(fetchOne(base + "-USDT-SWAP", MarketType.PERPETUAL));
        }
        return result;
    }

    private InstrumentRuleSnapshot fetchOne(String instrumentId, MarketType marketType) {
        String type = marketType == MarketType.SPOT ? "SPOT" : "SWAP";
        String body = get(properties.getRestBaseUrl() + "/api/v5/public/instruments?instType="
                + type + "&instId=" + instrumentId);
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"0".equals(root.path("code").asText())) {
                throw new IllegalStateException("OKX instruments rejected: " + root.path("code").asText());
            }
            JsonNode item = root.path("data").get(0);
            if (item == null) throw new IllegalStateException("OKX instrument missing: " + instrumentId);
            String base = item.path("baseCcy").asText();
            if (base.isBlank()) base = item.path("ctValCcy").asText();
            String quote = item.path("quoteCcy").asText("USDT");
            if (quote.isBlank()) quote = "USDT";
            String normalized = base + quote;
            BigDecimal multiplier = marketType == MarketType.SPOT
                    ? BigDecimal.ONE : decimal(item, "ctVal", BigDecimal.ONE);
            return new InstrumentRuleSnapshot(exchange(), marketType, normalized, instrumentId,
                    base, quote, marketType == MarketType.SPOT ? quote : item.path("settleCcy").asText(quote),
                    "live".equals(item.path("state").asText()) ? "TRADING" : "SUSPENDED",
                    decimal(item, "tickSz", BigDecimal.ZERO), decimal(item, "lotSz", BigDecimal.ZERO),
                    decimal(item, "minSz", BigDecimal.ZERO),
                    decimal(item, "maxLmtSz", BigDecimal.ZERO), null, multiplier,
                    "OKX:" + EventFingerprint.sha256(body).substring(0, 20));
        } catch (IOException e) {
            throw new IllegalStateException("Invalid OKX instruments payload", e);
        }
    }

    private String get(String url) {
        try (Response response = httpClient.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("OKX instruments HTTP " + response.code());
            }
            return response.body().string();
        } catch (IOException e) {
            throw new IllegalStateException("OKX instruments transport failed", e);
        }
    }

    private BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        String value = node.path(field).asText();
        return value.isBlank() ? fallback : new BigDecimal(value);
    }
}
