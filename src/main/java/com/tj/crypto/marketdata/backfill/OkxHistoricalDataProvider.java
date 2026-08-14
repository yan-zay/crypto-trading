package com.tj.crypto.marketdata.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.config.properties.OkxProperties;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.normalize.OkxKlineNormalizer;
import com.tj.crypto.marketdata.okx.OkxMarketDataMappings;
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

/** OKX public REST history-candles provider with backward cursor pagination. */
@Component
public class OkxHistoricalDataProvider implements ExchangeHistoricalDataProvider {

    static final int MAX_LIMIT = 300;
    private static final int MAX_PAGES = 10_000;

    private final OkHttpClient httpClient;
    private final OkxProperties properties;
    private final ObjectMapper objectMapper;
    private final OkxKlineNormalizer normalizer;

    public OkxHistoricalDataProvider(OkHttpClient httpClient, OkxProperties properties,
                                     ObjectMapper objectMapper, OkxKlineNormalizer normalizer) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
    }

    @Override
    public Exchange exchange() {
        return Exchange.OKX;
    }

    @Override
    public List<BarEvent> loadBars(Instrument instrument, Timeframe timeframe, long from, long to) {
        if (instrument.exchange() != Exchange.OKX) {
            throw new IllegalArgumentException("OKX provider requires an OKX instrument");
        }
        String instrumentId = OkxMarketDataMappings.toOkxInstrumentId(
                instrument.symbol(), instrument.marketType());
        String channel = OkxMarketDataMappings.websocketChannel(timeframe);
        Map<Long, BarEvent> bars = new TreeMap<>();
        long cursor = to + timeframe.getMillis();

        for (int pageNumber = 0; pageNumber < MAX_PAGES && cursor > from; pageNumber++) {
            String url = buildUrl(instrumentId, timeframe, cursor);
            List<BarEvent> page = fetchPage(url, instrumentId, channel);
            if (page.isEmpty()) break;

            long oldest = page.stream()
                    .mapToLong(bar -> bar.metadata().exchangeTimestamp()).min().orElse(cursor);
            page.stream()
                    .filter(BarEvent::closed)
                    .filter(bar -> bar.metadata().exchangeTimestamp() >= from)
                    .filter(bar -> bar.metadata().exchangeTimestamp() <= to)
                    .forEach(bar -> bars.put(bar.metadata().exchangeTimestamp(), bar));

            if (oldest >= cursor || oldest <= from || page.size() < MAX_LIMIT) break;
            cursor = oldest;
        }

        return new ArrayList<>(bars.values());
    }

    String buildUrl(String instrumentId, Timeframe timeframe, long beforeCursor) {
        HttpUrl base = HttpUrl.get(properties.getRestBaseUrl()
                + "/api/v5/market/history-candles");
        return base.newBuilder()
                .addQueryParameter("instId", instrumentId)
                .addQueryParameter("bar", OkxMarketDataMappings.restBar(timeframe))
                .addQueryParameter("after", Long.toString(beforeCursor))
                .addQueryParameter("limit", Integer.toString(MAX_LIMIT))
                .build().toString();
    }

    List<BarEvent> fetchPage(String url, String instrumentId, String channel) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new HistoricalDataAccessException(
                        "OKX historical data request failed with HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new HistoricalDataAccessException(
                        "OKX historical data response body is empty");
            }
            return parseResponse(body.string(), instrumentId, channel);
        } catch (IOException e) {
            throw new HistoricalDataAccessException("OKX historical data request failed", e);
        }
    }

    List<BarEvent> parseResponse(String json, String instrumentId, String channel) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!"0".equals(root.path("code").asText()) || !root.path("data").isArray()) {
                throw new HistoricalDataAccessException(
                        "OKX historical data API error: code=" + root.path("code").asText());
            }
            List<BarEvent> bars = new ArrayList<>();
            for (JsonNode candle : root.path("data")) {
                BarEvent bar = normalizer.normalize(
                        instrumentId, channel, candle, System.currentTimeMillis());
                if (bar != null) bars.add(bar);
            }
            if (!root.path("data").isEmpty() && bars.isEmpty()) {
                throw new HistoricalDataAccessException(
                        "OKX historical data page contained no valid candles");
            }
            bars.sort(Comparator.comparingLong(bar -> bar.metadata().exchangeTimestamp()));
            return bars;
        } catch (IOException e) {
            throw new HistoricalDataAccessException(
                    "Cannot parse OKX historical data response", e);
        }
    }
}
