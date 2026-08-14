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

/**
 * Binance Kline 数据标准化器。
 * 将 Binance WebSocket 推送的 kline JSON 转换为内部 BarEvent。
 *
 * 输入格式（Binance kline stream）：
 * {
 *   "e": "kline",
 *   "E": 1672515782136,
 *   "s": "BTCUSDT",
 *   "k": {
 *     "t": 1672515780000, "T": 1672515839999,
 *     "s": "BTCUSDT", "i": "1m",
 *     "o": "16721.50", "h": "16722.00", "l": "16721.00", "c": "16721.50",
 *     "v": "100.5", "q": "1679231.25",
 *     "x": false
 *   }
 * }
 */
@Slf4j
@Component
public class BinanceKlineNormalizer {

    /**
     * 将 Binance kline JSON 转换为 BarEvent。
     *
     * @param klineNode Binance kline JSON 中的 "k" 节点
     * @param eventTime Binance 事件时间（"E" 字段）
     * @return BarEvent，解析失败时返回 null
     */
    public BarEvent normalize(JsonNode klineNode, long eventTime) {
        return normalize(klineNode, eventTime, MarketType.PERPETUAL);
    }

    /** Normalize a Binance candle while preserving the source market identity. */
    public BarEvent normalize(JsonNode klineNode, long eventTime, MarketType marketType) {
        try {
            if (marketType != MarketType.SPOT && marketType != MarketType.PERPETUAL) {
                throw new IllegalArgumentException(
                        "Binance K-line market must be SPOT or PERPETUAL");
            }
            String symbol = klineNode.get("s").asText();
            String interval = klineNode.get("i").asText();

            Instrument instrument = Instrument.of(Exchange.BINANCE, marketType, symbol);
            Timeframe timeframe = Timeframe.fromCode(interval);

            BigDecimal open = new BigDecimal(klineNode.get("o").asText());
            BigDecimal high = new BigDecimal(klineNode.get("h").asText());
            BigDecimal low = new BigDecimal(klineNode.get("l").asText());
            BigDecimal close = new BigDecimal(klineNode.get("c").asText());
            BigDecimal volume = new BigDecimal(klineNode.get("v").asText());
            BigDecimal quoteVolume = new BigDecimal(klineNode.get("q").asText());
            boolean closed = klineNode.get("x").asBoolean();

            long barStartTime = klineNode.get("t").asLong();
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, barStartTime);

            return new BarEvent(instrument, metadata, timeframe,
                    open, high, low, close, volume, quoteVolume, closed);

        } catch (Exception e) {
            log.error("Failed to normalize Binance kline: {}", e.getMessage(), e);
            return null;
        }
    }
}
