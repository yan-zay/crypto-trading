package com.tj.crypto.marketdata.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Converts CoinGlass API v4 price-history objects into canonical completed candles. */
@Slf4j
@Component
public class CoinglassKlineNormalizer {

    public BarEvent normalize(String symbol, MarketType marketType, Timeframe timeframe,
                              JsonNode candle, long receivedTimestamp) {
        try {
            if (candle == null || !candle.isObject()) {
                throw new IllegalArgumentException("CoinGlass candle must be an object");
            }
            long openTime = candle.path("time").asLong(0L);
            if (openTime <= 0) throw new IllegalArgumentException("Missing candle time");
            BigDecimal quoteVolume = decimal(candle, "volume_usd", BigDecimal.ZERO);
            return new BarEvent(
                    Instrument.of(Exchange.COINGLASS, marketType, symbol),
                    new EventMetadata(Exchange.COINGLASS, openTime, receivedTimestamp, null),
                    timeframe,
                    decimal(candle, "open", null),
                    decimal(candle, "high", null),
                    decimal(candle, "low", null),
                    decimal(candle, "close", null),
                    BigDecimal.ZERO,
                    quoteVolume,
                    true);
        } catch (RuntimeException e) {
            log.warn("Failed to normalize CoinGlass {} {} candle: {}",
                    marketType, symbol, e.getMessage());
            return null;
        }
    }

    private BigDecimal decimal(JsonNode node, String field, BigDecimal defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            if (defaultValue != null) return defaultValue;
            throw new IllegalArgumentException("Missing candle field: " + field);
        }
        return new BigDecimal(value.asText());
    }
}
