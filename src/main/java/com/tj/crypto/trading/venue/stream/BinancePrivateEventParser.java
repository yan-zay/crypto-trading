package com.tj.crypto.trading.venue.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.venue.VenueOrderState;
import com.tj.crypto.trading.venue.binance.BinancePrivateRestGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BinancePrivateEventParser implements PrivateVenueEventParser {
    private final ObjectMapper objectMapper;

    @Override
    public List<VenuePrivateEvent> parse(String payload, MarketType marketType) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = text(root, "e");
            long eventTime = root.path("E").asLong(System.currentTimeMillis());
            if ("executionReport".equals(type)) {
                return List.of(spotOrder(root, marketType, payload, eventTime));
            }
            if ("ORDER_TRADE_UPDATE".equals(type)) {
                return List.of(futuresOrder(root.path("o"), marketType, payload, eventTime));
            }
            if ("outboundAccountPosition".equals(type) || "balanceUpdate".equals(type)
                    || "ACCOUNT_UPDATE".equals(type)) {
                String id = "BINANCE:ACCOUNT:" + eventTime + ":" + EventFingerprint.sha256(payload).substring(0, 16);
                return List.of(new VenueAccountRefresh(Exchange.BINANCE, id, eventTime, type));
            }
            return List.of();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid Binance private event", e);
        }
    }

    private VenueOrderUpdate spotOrder(JsonNode node, MarketType marketType,
                                        String payload, long eventTime) {
        return update(node, marketType, payload, eventTime,
                text(node, "i"), text(node, "c"), text(node, "t"), text(node, "X"),
                decimal(node, "q"), decimal(node, "z"), decimal(node, "l"),
                decimalOrNull(node, "L"), spotAverage(node),
                decimal(node, "n"), text(node, "N"), makerRole(node));
    }

    private VenueOrderUpdate futuresOrder(JsonNode node, MarketType marketType,
                                           String payload, long eventTime) {
        return update(node, marketType, payload, eventTime,
                text(node, "i"), text(node, "c"), text(node, "t"), text(node, "X"),
                decimal(node, "q"), decimal(node, "z"), decimal(node, "l"),
                decimalOrNull(node, "L"), decimalOrNull(node, "ap"),
                decimal(node, "n"), text(node, "N"), makerRole(node));
    }

    private VenueOrderUpdate update(JsonNode node, MarketType marketType, String payload,
                                    long eventTime, String orderId, String clientOrderId,
                                    String tradeId, String rawStatus, BigDecimal originalQuantity,
                                    BigDecimal cumulative, BigDecimal lastQuantity,
                                    BigDecimal lastPrice, BigDecimal averagePrice,
                                    BigDecimal fee, String feeCurrency, String role) {
        String checksum = EventFingerprint.sha256(payload);
        String externalId = "BINANCE:" + orderId + ":" + eventTime + ":"
                + cumulative.toPlainString() + ":" + rawStatus;
        return new VenueOrderUpdate(Exchange.BINANCE, externalId, checksum, eventTime,
                Instrument.of(Exchange.BINANCE, marketType, text(node, "s")), orderId,
                clientOrderId, tradeId, rawStatus, BinancePrivateRestGateway.mapStatus(rawStatus),
                originalQuantity, cumulative, lastQuantity, lastPrice, averagePrice,
                fee, feeCurrency, role);
    }

    private String makerRole(JsonNode node) {
        return node.path("m").asBoolean(false) ? "MAKER" : "TAKER";
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

    private BigDecimal spotAverage(JsonNode node) {
        BigDecimal cumulative = decimal(node, "z");
        BigDecimal cumulativeQuote = decimal(node, "Z");
        return cumulative.signum() == 0 ? null
                : cumulativeQuote.divide(cumulative, 18, java.math.RoundingMode.HALF_UP);
    }
}
