package com.tj.crypto.marketdata.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BinanceKlineNormalizer 单元测试。
 * 使用本地 sample JSON，不依赖网络。
 */
class BinanceKlineNormalizerTest {

    private BinanceKlineNormalizer normalizer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        normalizer = new BinanceKlineNormalizer();
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("正常 kline 解析")
    class NormalKline {

        @Test
        @DisplayName("应正确解析完整 kline JSON 为 BarEvent")
        void shouldParseKlineJsonToBarEvent() throws Exception {
            // Binance kline stream 中的 "k" 节点
            String json = """
                    {
                        "t": 1672515780000,
                        "T": 1672515839999,
                        "s": "BTCUSDT",
                        "i": "1m",
                        "o": "16721.50",
                        "h": "16722.00",
                        "l": "16721.00",
                        "c": "16721.50",
                        "v": "100.5",
                        "q": "1679231.25",
                        "x": false
                    }
                    """;
            JsonNode klineNode = objectMapper.readTree(json);

            BarEvent result = normalizer.normalize(klineNode, 1672515782136L);

            assertThat(result).isNotNull();
            assertThat(result.instrument().exchange()).isEqualTo(Exchange.BINANCE);
            assertThat(result.instrument().marketType()).isEqualTo(MarketType.PERPETUAL);
            assertThat(result.instrument().symbol()).isEqualTo("BTCUSDT");
            assertThat(result.instrument().baseAsset()).isEqualTo("BTC");
            assertThat(result.instrument().quoteAsset()).isEqualTo("USDT");
            assertThat(result.timeframe()).isEqualTo(Timeframe.M1);
            assertThat(result.open()).isEqualByComparingTo(new BigDecimal("16721.50"));
            assertThat(result.high()).isEqualByComparingTo(new BigDecimal("16722.00"));
            assertThat(result.low()).isEqualByComparingTo(new BigDecimal("16721.00"));
            assertThat(result.close()).isEqualByComparingTo(new BigDecimal("16721.50"));
            assertThat(result.volume()).isEqualByComparingTo(new BigDecimal("100.5"));
            assertThat(result.quoteVolume()).isEqualByComparingTo(new BigDecimal("1679231.25"));
            assertThat(result.closed()).isFalse();
            assertThat(result.metadata().source()).isEqualTo(Exchange.BINANCE);
            assertThat(result.metadata().exchangeTimestamp()).isEqualTo(1672515780000L);
        }

        @Test
        @DisplayName("应正确解析已关闭的 kline")
        void shouldParseClosedKline() throws Exception {
            String json = """
                    {
                        "t": 1672515780000,
                        "T": 1672515839999,
                        "s": "ETHUSDT",
                        "i": "5m",
                        "o": "1200.00",
                        "h": "1210.50",
                        "l": "1195.00",
                        "c": "1205.75",
                        "v": "500.25",
                        "q": "602500.50",
                        "x": true
                    }
                    """;
            JsonNode klineNode = objectMapper.readTree(json);

            BarEvent result = normalizer.normalize(klineNode, 1672515782136L);

            assertThat(result).isNotNull();
            assertThat(result.instrument().symbol()).isEqualTo("ETHUSDT");
            assertThat(result.timeframe()).isEqualTo(Timeframe.M5);
            assertThat(result.closed()).isTrue();
        }

        @Test
        @DisplayName("应正确解析 1h 时间周期")
        void shouldParse1hTimeframe() throws Exception {
            String json = """
                    {
                        "t": 1672515600000,
                        "T": 1672519199999,
                        "s": "BTCUSDT",
                        "i": "1h",
                        "o": "16700.00",
                        "h": "16800.00",
                        "l": "16650.00",
                        "c": "16750.00",
                        "v": "1000.0",
                        "q": "16750000.00",
                        "x": false
                    }
                    """;
            JsonNode klineNode = objectMapper.readTree(json);

            BarEvent result = normalizer.normalize(klineNode, 1672515782136L);

            assertThat(result).isNotNull();
            assertThat(result.timeframe()).isEqualTo(Timeframe.H1);
        }
    }

    @Nested
    @DisplayName("异常处理")
    class ErrorHandling {

        @Test
        @DisplayName("畸形 JSON 应返回 null 而非抛异常")
        void shouldReturnNullForMalformedJson() throws Exception {
            // 缺少必要字段
            String json = """
                    {
                        "s": "BTCUSDT"
                    }
                    """;
            JsonNode klineNode = objectMapper.readTree(json);

            BarEvent result = normalizer.normalize(klineNode, 1672515782136L);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("无效时间周期应返回 null")
        void shouldReturnNullForInvalidTimeframe() throws Exception {
            String json = """
                    {
                        "t": 1672515780000,
                        "T": 1672515839999,
                        "s": "BTCUSDT",
                        "i": "2m",
                        "o": "16721.50",
                        "h": "16722.00",
                        "l": "16721.00",
                        "c": "16721.50",
                        "v": "100.5",
                        "q": "1679231.25",
                        "x": false
                    }
                    """;
            JsonNode klineNode = objectMapper.readTree(json);

            BarEvent result = normalizer.normalize(klineNode, 1672515782136L);

            assertThat(result).isNull();
        }
    }
}
