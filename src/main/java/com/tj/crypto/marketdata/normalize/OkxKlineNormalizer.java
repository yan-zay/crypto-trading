package com.tj.crypto.marketdata.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.okx.OkxMarketDataMappings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Converts OKX candle arrays into the canonical BarEvent model. */
@Slf4j
@Component
public class OkxKlineNormalizer {

    private static final int MIN_FIELDS = 9;

    public BarEvent normalize(String instrumentId, String channel,
                              JsonNode candle, long receivedTimestamp) {
        try {
            if (candle == null || !candle.isArray() || candle.size() < MIN_FIELDS) {
                throw new IllegalArgumentException("OKX candle requires at least 9 fields");
            }
            MarketType marketType = OkxMarketDataMappings.marketType(instrumentId);
            Instrument instrument = Instrument.of(Exchange.OKX, marketType,
                    OkxMarketDataMappings.toInternalSymbol(instrumentId));
            Timeframe timeframe = OkxMarketDataMappings.timeframeFromChannel(channel);
            long openTime = candle.get(0).asLong();
            EventMetadata metadata = new EventMetadata(
                    Exchange.OKX, openTime, receivedTimestamp, null);
            // OKX derivatives field 5 is contract count; canonical volume is base-asset volume.
            BigDecimal baseVolume = marketType == MarketType.PERPETUAL
                    ? decimal(candle, 6) : decimal(candle, 5);
            return new BarEvent(
                    instrument, metadata, timeframe,
                    decimal(candle, 1), decimal(candle, 2), decimal(candle, 3), decimal(candle, 4),
                    baseVolume, decimal(candle, 7), "1".equals(candle.get(8).asText()));
        } catch (RuntimeException e) {
            log.warn("Failed to normalize OKX candle for {} {}: {}",
                    instrumentId, channel, e.getMessage());
            return null;
        }
    }

    private BigDecimal decimal(JsonNode candle, int index) {
        return new BigDecimal(candle.get(index).asText());
    }
}
