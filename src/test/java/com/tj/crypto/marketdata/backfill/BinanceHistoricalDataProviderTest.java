package com.tj.crypto.marketdata.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.BinanceHistoricalDataProperties;
import com.tj.crypto.marketdata.model.BarEvent;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BinanceHistoricalDataProvider 单元测试。
 * 使用本地 sample 数据和 Mock OkHttp，不依赖真实网络。
 */
@ExtendWith(MockitoExtension.class)
class BinanceHistoricalDataProviderTest {

    private static final Instrument BTC_INSTRUMENT =
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

    @Mock
    private OkHttpClient httpClient;

    private BinanceHistoricalDataProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        BinanceHistoricalDataProperties properties = new BinanceHistoricalDataProperties();
        properties.setBaseUrl("https://fapi.binance.com");
        provider = new BinanceHistoricalDataProvider(httpClient, properties, objectMapper);
    }

    /**
     * 构建 Mock Response 的辅助方法。
     */
    private Response buildMockResponse(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://fapi.binance.com/fapi/v1/klines").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.parse("application/json")))
                .build();
    }

    @Nested
    @DisplayName("JSON 解析")
    class JsonParsing {

        @Test
        @DisplayName("应正确解析单根 K 线数组")
        void shouldParseSingleKline() {
            // Binance REST API 返回格式：[openTime, open, high, low, close, volume, closeTime, quoteVolume, ...]
            String json = """
                    [
                        [1499040000000, "0.01634000", "0.80000000", "0.01575800", "0.01577100", "148976.11427815", 1499644799999, "2434.19055334", 1756, "1756.87402397", "0", "0"]
                    ]
                    """;

            List<BarEvent> bars = provider.parseResponse(json, BTC_INSTRUMENT, Timeframe.M1);

            assertThat(bars).hasSize(1);
            BarEvent bar = bars.get(0);
            assertThat(bar.instrument().symbol()).isEqualTo("BTCUSDT");
            assertThat(bar.instrument().exchange()).isEqualTo(Exchange.BINANCE);
            assertThat(bar.instrument().marketType()).isEqualTo(MarketType.PERPETUAL);
            assertThat(bar.timeframe()).isEqualTo(Timeframe.M1);
            assertThat(bar.metadata().exchangeTimestamp()).isEqualTo(1499040000000L);
            assertThat(bar.open()).isEqualByComparingTo(new BigDecimal("0.01634000"));
            assertThat(bar.high()).isEqualByComparingTo(new BigDecimal("0.80000000"));
            assertThat(bar.low()).isEqualByComparingTo(new BigDecimal("0.01575800"));
            assertThat(bar.close()).isEqualByComparingTo(new BigDecimal("0.01577100"));
            assertThat(bar.volume()).isEqualByComparingTo(new BigDecimal("148976.11427815"));
            assertThat(bar.quoteVolume()).isEqualByComparingTo(new BigDecimal("2434.19055334"));
            assertThat(bar.closed()).isTrue();
        }

        @Test
        @DisplayName("应正确解析多根 K 线数组")
        void shouldParseMultipleKlines() {
            String json = """
                    [
                        [1499040000000, "0.01634000", "0.80000000", "0.01575800", "0.01577100", "148976.11427815", 1499644799999, "2434.19055334", 1756, "1756.87402397", "0", "0"],
                        [1499040060000, "0.01577100", "0.01580000", "0.01570000", "0.01575000", "200.00000000", 1499040119999, "3150.00000000", 100, "100.00000000", "0", "0"],
                        [1499040120000, "0.01575000", "0.01600000", "0.01570000", "0.01590000", "300.00000000", 1499040179999, "4770.00000000", 150, "150.00000000", "0", "0"]
                    ]
                    """;

            List<BarEvent> bars = provider.parseResponse(json, BTC_INSTRUMENT, Timeframe.M1);

            assertThat(bars).hasSize(3);
            assertThat(bars.get(0).metadata().exchangeTimestamp()).isEqualTo(1499040000000L);
            assertThat(bars.get(1).metadata().exchangeTimestamp()).isEqualTo(1499040060000L);
            assertThat(bars.get(2).metadata().exchangeTimestamp()).isEqualTo(1499040120000L);
        }

        @Test
        @DisplayName("应正确解析 ETH 交易对")
        void shouldParseEthKline() {
            Instrument ethInstrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT");
            String json = """
                    [
                        [1672515600000, "1200.00", "1210.50", "1195.00", "1205.75", "500.25", 1672519199999, "602500.50", 200, "250.00", "0", "0"]
                    ]
                    """;

            List<BarEvent> bars = provider.parseResponse(json, ethInstrument, Timeframe.H1);

            assertThat(bars).hasSize(1);
            assertThat(bars.get(0).instrument().symbol()).isEqualTo("ETHUSDT");
            assertThat(bars.get(0).instrument().baseAsset()).isEqualTo("ETH");
            assertThat(bars.get(0).timeframe()).isEqualTo(Timeframe.H1);
            assertThat(bars.get(0).close()).isEqualByComparingTo(new BigDecimal("1205.75"));
        }

        @Test
        @DisplayName("空数组应返回空列表")
        void shouldReturnEmptyForEmptyArray() {
            String json = "[]";

            List<BarEvent> bars = provider.parseResponse(json, BTC_INSTRUMENT, Timeframe.M1);

            assertThat(bars).isEmpty();
        }

        @Test
        @DisplayName("畸形 JSON 应返回空列表")
        void shouldReturnEmptyForMalformedJson() {
            String json = "not valid json";

            List<BarEvent> bars = provider.parseResponse(json, BTC_INSTRUMENT, Timeframe.M1);

            assertThat(bars).isEmpty();
        }

        @Test
        @DisplayName("非数组 JSON 应返回空列表")
        void shouldReturnEmptyForNonArrayJson() {
            String json = "{\"error\": \"invalid\"}";

            List<BarEvent> bars = provider.parseResponse(json, BTC_INSTRUMENT, Timeframe.M1);

            assertThat(bars).isEmpty();
        }

        @Test
        @DisplayName("字段不足的 K 线应被跳过")
        void shouldSkipKlineWithInsufficientFields() {
            String json = """
                    [
                        [1499040000000, "0.01634000", "0.80000000"]
                    ]
                    """;

            List<BarEvent> bars = provider.parseResponse(json, BTC_INSTRUMENT, Timeframe.M1);

            assertThat(bars).isEmpty();
        }
    }

    @Nested
    @DisplayName("URL 构建")
    class UrlBuilding {

        @Test
        @DisplayName("应正确构建 Binance klines URL")
        void shouldBuildCorrectUrl() {
            String url = provider.buildUrl("BTCUSDT", Timeframe.M1, 1499040000000L, 1499644799999L);

            assertThat(url).contains("symbol=BTCUSDT");
            assertThat(url).contains("interval=1m");
            assertThat(url).contains("startTime=1499040000000");
            assertThat(url).contains("endTime=1499644799999");
            assertThat(url).contains("limit=1500");
            assertThat(url).startsWith("https://fapi.binance.com/fapi/v1/klines");
        }

        @Test
        @DisplayName("应正确使用 5m 时间周期")
        void shouldBuildUrlWith5mTimeframe() {
            String url = provider.buildUrl("ETHUSDT", Timeframe.M5, 1000000000000L, 1000086400000L);

            assertThat(url).contains("symbol=ETHUSDT");
            assertThat(url).contains("interval=5m");
        }
    }

    @Nested
    @DisplayName("分页逻辑")
    class Pagination {

        @Test
        @DisplayName("单页不足 limit 时应只请求一次")
        void shouldStopWhenPageIsSmallerThanLimit() throws IOException {
            String singlePageJson = """
                    [
                        [1499040000000, "0.01634000", "0.80000000", "0.01575800", "0.01577100", "148976.11427815", 1499644799999, "2434.19055334", 1756, "1756.87402397", "0", "0"]
                    ]
                    """;

            Call call = mock(Call.class);
            when(call.execute()).thenReturn(buildMockResponse(200, singlePageJson));
            when(httpClient.newCall(any(Request.class))).thenReturn(call);

            List<BarEvent> bars = provider.loadBars(BTC_INSTRUMENT, Timeframe.M1,
                    1499040000000L, 1499644799999L);

            assertThat(bars).hasSize(1);
            verify(httpClient, times(1)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("返回满页时应继续请求下一页")
        void shouldContinuePagingWhenFullPageReturned() throws IOException {
            // 构造 1500 根 K 线的 JSON 响应
            StringBuilder sb = new StringBuilder("[");
            long baseTime = 1499040000000L;
            for (int i = 0; i < 1500; i++) {
                long openTime = baseTime + (long) i * 60000;
                long closeTime = openTime + 59999;
                if (i > 0) sb.append(",");
                sb.append(String.format("[%d, \"100.00\", \"110.00\", \"90.00\", \"105.00\", \"50.00\", %d, \"5000.00\", 10, \"25.00\", \"0\", \"0\"]",
                        openTime, closeTime));
            }
            sb.append("]");
            String fullPageJson = sb.toString();

            // 第二页返回少量数据
            String secondPageJson = """
                    [
                        [1589040000000, "100.00", "110.00", "90.00", "105.00", "50.00", 1589040059999, "5000.00", 10, "25.00", "0", "0"]
                    ]
                    """;

            Call firstCall = mock(Call.class);
            when(firstCall.execute()).thenReturn(buildMockResponse(200, fullPageJson));

            Call secondCall = mock(Call.class);
            when(secondCall.execute()).thenReturn(buildMockResponse(200, secondPageJson));

            when(httpClient.newCall(any(Request.class)))
                    .thenReturn(firstCall)
                    .thenReturn(secondCall);

            List<BarEvent> bars = provider.loadBars(BTC_INSTRUMENT, Timeframe.M1,
                    baseTime, baseTime + 100_000_000_000L);

            assertThat(bars).hasSize(1501);
            verify(httpClient, times(2)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("API 返回错误状态码时应返回空列表")
        void shouldReturnEmptyOnApiError() throws IOException {
            Call call = mock(Call.class);
            when(call.execute()).thenReturn(buildMockResponse(429, "{\"error\":\"rate limit\"}"));
            when(httpClient.newCall(any(Request.class))).thenReturn(call);

            List<BarEvent> bars = provider.loadBars(BTC_INSTRUMENT, Timeframe.M1,
                    1499040000000L, 1499644799999L);

            assertThat(bars).isEmpty();
        }

        @Test
        @DisplayName("网络异常时应返回空列表")
        void shouldReturnEmptyOnIoException() throws IOException {
            Call call = mock(Call.class);
            when(call.execute()).thenThrow(new IOException("Connection refused"));
            when(httpClient.newCall(any(Request.class))).thenReturn(call);

            List<BarEvent> bars = provider.loadBars(BTC_INSTRUMENT, Timeframe.M1,
                    1499040000000L, 1499644799999L);

            assertThat(bars).isEmpty();
        }
    }
}
