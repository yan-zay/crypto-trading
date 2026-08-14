package com.tj.crypto.observability.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/** Generic authenticated webhook sink. Delivery failure is surfaced to the monitor and metrics. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "crypto.alerting.webhook-enabled", havingValue = "true")
public class WebhookAlertNotificationSink implements AlertNotificationSink {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${crypto.alerting.webhook-url:}")
    private String webhookUrl;

    @Value("${crypto.alerting.webhook-bearer-token:}")
    private String bearerToken;

    @Override
    public void send(AlertEvent event) {
        if (webhookUrl == null || webhookUrl.isBlank() || !webhookUrl.startsWith("https://")) {
            throw new IllegalStateException("Alert webhook must be a non-empty HTTPS URL");
        }
        Request.Builder request = new Request.Builder().url(webhookUrl)
                .post(RequestBody.create(payload(event), JSON));
        if (bearerToken != null && !bearerToken.isBlank()) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        try (Response response = httpClient.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Alert webhook returned HTTP " + response.code());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Alert webhook delivery failed", e);
        }
    }

    private String payload(AlertEvent event) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "ruleName", event.ruleName(),
                    "severity", event.severity().name(),
                    "message", event.message(),
                    "timestamp", event.timestamp(),
                    "resolved", event.resolved()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Alert payload serialization failed", e);
        }
    }
}
