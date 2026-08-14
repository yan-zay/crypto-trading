package com.tj.crypto.trading.venue.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Self-healing Binance Spot and USD-M private user streams. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinancePrivateUserStreamService implements PrivateStreamStatusProvider {
    private static final RequestBody EMPTY = RequestBody.create(new byte[0], MediaType.get("application/json"));
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PrivateTradingProperties properties;
    private final BinancePrivateEventParser parser;
    private final VenuePrivateEventProcessor processor;
    private final Map<MarketType, Session> sessions = new EnumMap<>(MarketType.class);

    @Scheduled(fixedDelayString = "${crypto.private-trading.stream-health-interval-ms:30000}")
    public synchronized void ensureConnected() {
        if (!enabled() || !configured()) {
            closeAll();
            return;
        }
        connectIfNeeded(MarketType.SPOT);
        connectIfNeeded(MarketType.PERPETUAL);
    }

    @Scheduled(fixedDelayString = "${crypto.private-trading.binance.listen-key-keepalive-ms:1500000}")
    public synchronized void keepAlive() {
        if (!enabled() || !configured()) return;
        for (Map.Entry<MarketType, Session> entry : sessions.entrySet()) {
            if (entry.getValue().listenKey != null) {
                try {
                    listenKey(entry.getKey(), "PUT", entry.getValue().listenKey);
                } catch (RuntimeException e) {
                    entry.getValue().lastError = "listen-key keepalive failed";
                    entry.getValue().close();
                }
            }
        }
    }

    @Override
    public synchronized List<PrivateStreamStatus> statuses() {
        return List.of(status(MarketType.SPOT), status(MarketType.PERPETUAL));
    }

    @PreDestroy
    public synchronized void shutdown() {
        closeAll();
    }

    private void connectIfNeeded(MarketType marketType) {
        Session current = sessions.get(marketType);
        if (current != null && current.connected) return;
        if (current != null) current.close();
        try {
            String key = listenKey(marketType, "POST", null);
            String base = marketType == MarketType.SPOT
                    ? properties.getBinance().getSpotUserStreamUrl()
                    : properties.getBinance().getPerpetualUserStreamUrl();
            Session session = new Session(marketType, key);
            sessions.put(marketType, session);
            session.webSocket = httpClient.newWebSocket(
                    new Request.Builder().url(base + "/" + key).build(), session.listener());
        } catch (RuntimeException e) {
            Session failed = new Session(marketType, null);
            failed.lastError = e.getMessage();
            sessions.put(marketType, failed);
            log.warn("Binance {} private stream connection failed: {}", marketType, e.getMessage());
        }
    }

    private String listenKey(MarketType marketType, String method, String existing) {
        String base = marketType == MarketType.SPOT
                ? properties.getBinance().getSpotRestBaseUrl()
                : properties.getBinance().getPerpetualRestBaseUrl();
        String path = marketType == MarketType.SPOT ? "/api/v3/userDataStream" : "/fapi/v1/listenKey";
        String url = base + path + (existing == null ? "" : "?listenKey=" + existing);
        Request request = new Request.Builder().url(url)
                .header("X-MBX-APIKEY", properties.getBinance().getApiKey())
                .method(method, "POST".equals(method) || "PUT".equals(method) ? EMPTY : null)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("listen-key HTTP " + response.code());
            if (existing != null) return existing;
            JsonNode json = objectMapper.readTree(response.body() == null ? "{}" : response.body().string());
            String key = json.path("listenKey").asText();
            if (key.isBlank()) throw new IllegalStateException("listen-key missing");
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("listen-key transport failed", e);
        }
    }

    private PrivateStreamStatus status(MarketType marketType) {
        Session session = sessions.get(marketType);
        return new PrivateStreamStatus("BINANCE", marketType.name(), enabled(),
                session != null && session.connected, session == null ? 0 : session.lastMessageAtMs,
                session == null ? null : session.lastError);
    }

    private boolean enabled() {
        return properties.isUserStreamsEnabled() && properties.getBinance().isEnabled();
    }

    private boolean configured() {
        return present(properties.getBinance().getApiKey()) && present(properties.getBinance().getSecretKey());
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private void closeAll() {
        sessions.values().forEach(Session::close);
        sessions.clear();
    }

    private final class Session {
        private final MarketType marketType;
        private final String listenKey;
        private volatile WebSocket webSocket;
        private volatile boolean connected;
        private volatile long lastMessageAtMs;
        private volatile String lastError;

        private Session(MarketType marketType, String listenKey) {
            this.marketType = marketType;
            this.listenKey = listenKey;
        }

        private WebSocketListener listener() {
            return new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    connected = true;
                    lastError = null;
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    lastMessageAtMs = System.currentTimeMillis();
                    try {
                        parser.parse(text, marketType).forEach(processor::process);
                    } catch (RuntimeException e) {
                        lastError = "event processing failed";
                        log.error("Binance {} private event processing failed: {}", marketType, e.getMessage());
                    }
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    connected = false;
                    webSocket.close(code, reason);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    connected = false;
                    lastError = t.getClass().getSimpleName();
                }
            };
        }

        private void close() {
            connected = false;
            if (webSocket != null) webSocket.close(1000, "shutdown");
        }
    }
}
