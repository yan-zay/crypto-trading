package com.tj.crypto.marketdata.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.CoinglassProperties;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.normalize.CoinglassKlineNormalizer;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** CoinGlass API v4 spot/futures OHLC history provider. */
@Component
public class CoinglassHistoricalDataProvider implements ExchangeHistoricalDataProvider {

    static final int MAX_LIMIT = 1_000;
    private static final int MAX_PAGES = 10_000;

    private final OkHttpClient httpClient;
    private final CoinglassProperties properties;
    private final ObjectMapper objectMapper;
    private final CoinglassKlineNormalizer normalizer;

    public CoinglassHistoricalDataProvider(OkHttpClient httpClient,
                                           CoinglassProperties properties,
                                           ObjectMapper objectMapper,
                                           CoinglassKlineNormalizer normalizer) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
    }

    @Override
    public Exchange exchange() {
        return Exchange.COINGLASS;
    }

    @Override
    public List<BarEvent> loadBars(Instrument instrument, Timeframe timeframe,
                                   long from, long to) {
        validate(instrument);
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "COINGLASS_API_KEY is required for CoinGlass candle history");
        }
        Map<Long, BarEvent> result = new TreeMap<>();
        long cursor = from;
        for (int pageNumber = 0; pageNumber < MAX_PAGES && cursor <= to; pageNumber++) {
            List<BarEvent> page = fetchPage(buildUrl(instrument, timeframe, cursor, to),
                    instrument, timeframe);
            if (page.isEmpty()) break;
            long newest = cursor;
            for (BarEvent bar : page) {
                long timestamp = bar.metadata().exchangeTimestamp();
                newest = Math.max(newest, timestamp);
                if (timestamp >= from && timestamp <= to) result.put(timestamp, bar);
            }
            long next = newest + timeframe.getMillis();
            if (page.size() < MAX_LIMIT || next <= cursor || next > to) break;
            cursor = next;
        }
        return new ArrayList<>(result.values());
    }

    String buildUrl(Instrument instrument, Timeframe timeframe, long from, long to) {
        validate(instrument);
        String path = instrument.marketType() == MarketType.SPOT
                ? "/api/spot/price/history" : "/api/futures/price/history";
        HttpUrl base = HttpUrl.get(properties.getRestBaseUrl() + path);
        return base.newBuilder()
                .addQueryParameter("exchange", properties.getPriceExchange())
                .addQueryParameter("symbol", instrument.symbol())
                .addQueryParameter("interval", timeframe.getCode())
                .addQueryParameter("limit", Integer.toString(MAX_LIMIT))
                .addQueryParameter("start_time", Long.toString(from))
                .addQueryParameter("end_time", Long.toString(to))
                .build().toString();
    }

    List<BarEvent> fetchPage(String url, Instrument instrument, Timeframe timeframe) {
        Request request = new Request.Builder()
                .url(url)
                .header("CG-API-KEY", properties.getApiKey())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new HistoricalDataAccessException(
                        "CoinGlass historical data request failed with HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new HistoricalDataAccessException(
                        "CoinGlass historical data response body is empty");
            }
            return parseResponse(body.string(), instrument, timeframe, System.currentTimeMillis());
        } catch (IOException e) {
            throw new HistoricalDataAccessException(
                    "CoinGlass historical data request failed", e);
        }
    }

    List<BarEvent> parseResponse(String json, Instrument instrument,
                                 Timeframe timeframe, long receivedTimestamp) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!"0".equals(root.path("code").asText()) || !root.path("data").isArray()) {
                throw new HistoricalDataAccessException(
                        "CoinGlass historical data API error: code="
                                + root.path("code").asText() + ", message=" + responseMessage(root));
            }
            List<BarEvent> bars = new ArrayList<>();
            for (JsonNode candle : root.path("data")) {
                BarEvent bar = normalizer.normalize(instrument.symbol(), instrument.marketType(),
                        timeframe, candle, receivedTimestamp);
                if (bar != null) bars.add(bar);
            }
            if (!root.path("data").isEmpty() && bars.isEmpty()) {
                throw new HistoricalDataAccessException(
                        "CoinGlass historical data page contained no valid candles");
            }
            bars.sort(Comparator.comparingLong(bar -> bar.metadata().exchangeTimestamp()));
            return bars;
        } catch (IOException e) {
            throw new HistoricalDataAccessException(
                    "Cannot parse CoinGlass historical data response", e);
        }
    }

    private String responseMessage(JsonNode root) {
        String message = root.path("msg").asText();
        return message.isBlank() ? root.path("message").asText() : message;
    }

    private void validate(Instrument instrument) {
        if (instrument.exchange() != Exchange.COINGLASS) {
            throw new IllegalArgumentException("CoinGlass provider requires a COINGLASS instrument");
        }
        if (instrument.marketType() != MarketType.SPOT
                && instrument.marketType() != MarketType.PERPETUAL) {
            throw new IllegalArgumentException(
                    "CoinGlass candle provider supports SPOT and PERPETUAL only");
        }
    }
}
