package com.tj.crypto.trading.venue.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import com.tj.crypto.trading.venue.crypto.HmacSigner;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Authenticated OKX v5 private stream with automatic login, subscription and reconnect. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxPrivateUserStreamService implements PrivateStreamStatusProvider {
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PrivateTradingProperties properties;
    private final OkxPrivateEventParser parser;
    private final VenuePrivateEventProcessor processor;
    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean subscribed;
    private volatile long lastMessageAtMs;
    private volatile String lastError;

    @Scheduled(fixedDelayString = "${crypto.private-trading.stream-health-interval-ms:30000}")
    public synchronized void ensureConnected() {
        if (!enabled() || !configured()) {
            close();
            return;
        }
        if (connected) return;
        close();
        webSocket = httpClient.newWebSocket(new Request.Builder()
                .url(properties.getOkx().getPrivateWebsocketUrl()).build(), listener());
    }

    @Override
    public List<PrivateStreamStatus> statuses() {
        return List.of(new PrivateStreamStatus("OKX", "PRIVATE", enabled(),
                connected && subscribed, lastMessageAtMs, lastError));
    }

    @PreDestroy
    public synchronized void shutdown() {
        close();
    }

    private WebSocketListener listener() {
        return new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                connected = true;
                lastError = null;
                socket.send(loginPayload());
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                lastMessageAtMs = System.currentTimeMillis();
                try {
                    JsonNode root = objectMapper.readTree(text);
                    String event = root.path("event").asText();
                    if ("login".equals(event)) {
                        if (!"0".equals(root.path("code").asText())) {
                            lastError = "login rejected: " + root.path("code").asText();
                            close();
                            return;
                        }
                        socket.send(subscriptionPayload());
                        return;
                    }
                    if ("subscribe".equals(event)) {
                        subscribed = true;
                        return;
                    }
                    if ("error".equals(event)) {
                        lastError = "stream error: " + root.path("code").asText();
                        return;
                    }
                    parser.parse(text, MarketType.PERPETUAL).forEach(processor::process);
                } catch (RuntimeException | JsonProcessingException e) {
                    lastError = "event processing failed";
                    log.error("OKX private event processing failed: {}", e.getMessage());
                }
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                connected = false;
                subscribed = false;
                socket.close(code, reason);
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                connected = false;
                subscribed = false;
                lastError = t.getClass().getSimpleName();
            }
        };
    }

    private String loginPayload() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = HmacSigner.sha256Base64(properties.getOkx().getSecretKey(),
                timestamp + "GET/users/self/verify");
        return json(Map.of("op", "login", "args", List.of(Map.of(
                "apiKey", properties.getOkx().getApiKey(),
                "passphrase", properties.getOkx().getPassphrase(),
                "timestamp", timestamp,
                "sign", signature))));
    }

    private String subscriptionPayload() {
        return json(Map.of("op", "subscribe", "args", List.of(
                Map.of("channel", "orders", "instType", "ANY"),
                Map.of("channel", "account"),
                Map.of("channel", "positions", "instType", "ANY"))));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Private stream payload is not serializable", e);
        }
    }

    private boolean enabled() {
        return properties.isUserStreamsEnabled() && properties.getOkx().isEnabled();
    }

    private boolean configured() {
        return present(properties.getOkx().getApiKey()) && present(properties.getOkx().getSecretKey())
                && present(properties.getOkx().getPassphrase());
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private synchronized void close() {
        connected = false;
        subscribed = false;
        if (webSocket != null) webSocket.close(1000, "shutdown");
        webSocket = null;
    }
}
