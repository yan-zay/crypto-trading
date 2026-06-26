package com.tj.crypto.marketdata.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.BinanceHistoricalDataProperties;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Binance REST API 历史 K 线数据提供者。
 * <p>
 * 通过 GET /fapi/v1/klines 分页拉取历史 K 线数据，
 * 单次最多返回 1500 根，自动循环请求直到覆盖完整时间范围。
 */
@Slf4j
@Component
public class BinanceHistoricalDataProvider implements HistoricalDataProvider {

    private static final int MAX_LIMIT = 1500;
    private static final int KLINE_ARRAY_SIZE = 12;

    // Binance kline JSON array field indices
    private static final int IDX_OPEN_TIME = 0;
    private static final int IDX_OPEN = 1;
    private static final int IDX_HIGH = 2;
    private static final int IDX_LOW = 3;
    private static final int IDX_CLOSE = 4;
    private static final int IDX_VOLUME = 5;
    private static final int IDX_CLOSE_TIME = 6;
    private static final int IDX_QUOTE_VOLUME = 7;

    private final OkHttpClient httpClient;
    private final BinanceHistoricalDataProperties properties;
    private final ObjectMapper objectMapper;

    public BinanceHistoricalDataProvider(OkHttpClient httpClient,
                                         BinanceHistoricalDataProperties properties,
                                         ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<BarEvent> loadBars(Instrument instrument, Timeframe timeframe, long from, long to) {
        List<BarEvent> allBars = new ArrayList<>();
        long currentStart = from;

        while (currentStart < to) {
            String url = buildUrl(instrument.symbol(), timeframe, currentStart, to);
            List<BarEvent> page = fetchPage(url, instrument, timeframe);

            if (page.isEmpty()) {
                break;
            }

            allBars.addAll(page);

            // 下一页起始时间 = 最后一根 K 线的 closeTime + 1
            long lastCloseTime = page.get(page.size() - 1).metadata().exchangeTimestamp()
                    + timeframe.getMillis() - 1;
            currentStart = lastCloseTime + 1;

            // 如果返回数量不足 limit，说明已到末尾
            if (page.size() < MAX_LIMIT) {
                break;
            }
        }

        log.info("Loaded {} bars for {} {} from {} to {}", allBars.size(),
                instrument.symbol(), timeframe.getCode(), from, to);
        return allBars;
    }

    /**
     * 构建 Binance klines API URL。
     */
    String buildUrl(String symbol, Timeframe timeframe, long startTime, long endTime) {
        return String.format("%s/fapi/v1/klines?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d",
                properties.getBaseUrl(), symbol, timeframe.getCode(), startTime, endTime, MAX_LIMIT);
    }

    /**
     * 请求单页数据并解析为 BarEvent 列表。
     */
    List<BarEvent> fetchPage(String url, Instrument instrument, Timeframe timeframe) {
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Binance API request failed: HTTP {} for URL {}", response.code(), url);
                return List.of();
            }

            ResponseBody body = response.body();
            if (body == null) {
                log.warn("Binance API returned empty body for URL {}", url);
                return List.of();
            }

            return parseResponse(body.string(), instrument, timeframe);
        } catch (IOException e) {
            log.error("Failed to fetch klines from {}: {}", url, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 解析 Binance klines JSON 数组响应为 BarEvent 列表。
     *
     * @param json       JSON 响应字符串
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @return BarEvent 列表
     */
    List<BarEvent> parseResponse(String json, Instrument instrument, Timeframe timeframe) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                log.error("Unexpected Binance API response format: not an array");
                return List.of();
            }

            List<BarEvent> bars = new ArrayList<>(root.size());
            for (JsonNode kline : root) {
                BarEvent bar = parseKlineArray(kline, instrument, timeframe);
                if (bar != null) {
                    bars.add(bar);
                }
            }
            return bars;
        } catch (IOException e) {
            log.error("Failed to parse Binance klines response: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 解析单根 K 线 JSON 数组为 BarEvent。
     * <p>
     * Binance 返回格式：[openTime, open, high, low, close, volume, closeTime, quoteVolume, ...]
     *
     * @param kline      单根 K 线的 JsonNode（数组）
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @return BarEvent，解析失败返回 null
     */
    BarEvent parseKlineArray(JsonNode kline, Instrument instrument, Timeframe timeframe) {
        try {
            if (!kline.isArray() || kline.size() < KLINE_ARRAY_SIZE) {
                log.warn("Invalid kline array size: {}", kline.size());
                return null;
            }

            long openTime = kline.get(IDX_OPEN_TIME).asLong();
            BigDecimal open = new BigDecimal(kline.get(IDX_OPEN).asText());
            BigDecimal high = new BigDecimal(kline.get(IDX_HIGH).asText());
            BigDecimal low = new BigDecimal(kline.get(IDX_LOW).asText());
            BigDecimal close = new BigDecimal(kline.get(IDX_CLOSE).asText());
            BigDecimal volume = new BigDecimal(kline.get(IDX_VOLUME).asText());
            BigDecimal quoteVolume = new BigDecimal(kline.get(IDX_QUOTE_VOLUME).asText());

            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, openTime);
            return new BarEvent(instrument, metadata, timeframe,
                    open, high, low, close, volume, quoteVolume, true);
        } catch (Exception e) {
            log.error("Failed to parse kline array: {}", e.getMessage(), e);
            return null;
        }
    }
}
