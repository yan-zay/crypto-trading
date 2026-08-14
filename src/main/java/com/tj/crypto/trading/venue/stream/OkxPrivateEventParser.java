package com.tj.crypto.trading.venue.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.venue.okx.OkxPrivateRestGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OkxPrivateEventParser implements PrivateVenueEventParser {
    private final ObjectMapper objectMapper;

    @Override
    public List<VenuePrivateEvent> parse(String payload, MarketType ignored) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String channel = root.path("arg").path("channel").asText();
            if ("account".equals(channel) || "positions".equals(channel)) {
                long eventTime = latestTime(root.path("data"));
                return List.of(new VenueAccountRefresh(Exchange.OKX,
                        "OKX:" + channel + ":" + eventTime + ":"
                                + EventFingerprint.sha256(payload).substring(0, 16),
                        eventTime, channel));
            }
            if (!"orders".equals(channel)) return List.of();
            List<VenuePrivateEvent> events = new ArrayList<>();
            String checksum = EventFingerprint.sha256(payload);
            for (JsonNode item : root.path("data")) events.add(order(item, checksum));
            return events;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid OKX private event", e);
        }
    }

    private VenueOrderUpdate order(JsonNode item, String checksum) {
        long eventTime = longValue(item, "uTime", System.currentTimeMillis());
        String instrumentId = text(item, "instId");
        MarketType marketType = instrumentId.endsWith("-SWAP") ? MarketType.PERPETUAL : MarketType.SPOT;
        String symbol = instrumentId.replace("-SWAP", "").replace("-", "");
        String rawStatus = text(item, "state");
        BigDecimal cumulative = decimal(item, "accFillSz");
        String orderId = text(item, "ordId");
        String externalId = "OKX:" + orderId + ":" + eventTime + ":"
                + cumulative.toPlainString() + ":" + rawStatus;
        return new VenueOrderUpdate(Exchange.OKX, externalId, checksum, eventTime,
                Instrument.of(Exchange.OKX, marketType, symbol), orderId, text(item, "clOrdId"),
                text(item, "tradeId"), rawStatus, OkxPrivateRestGateway.mapStatus(rawStatus),
                decimal(item, "sz"), cumulative, decimal(item, "fillSz"),
                decimalOrNull(item, "fillPx"), decimalOrNull(item, "avgPx"),
                decimal(item, "fee").abs(), text(item, "feeCcy"),
                "M".equalsIgnoreCase(item.path("execType").asText()) ? "MAKER" : "TAKER");
    }

    private long latestTime(JsonNode data) {
        long latest = 0;
        for (JsonNode item : data) latest = Math.max(latest, longValue(item, "uTime", 0));
        return latest == 0 ? System.currentTimeMillis() : latest;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        BigDecimal value = decimalOrNull(node, field);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private long longValue(JsonNode node, String field, long fallback) {
        String value = text(node, field);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
