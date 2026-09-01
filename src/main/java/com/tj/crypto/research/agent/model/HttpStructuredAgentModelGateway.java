package com.tj.crypto.research.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.config.properties.AgentModelProperties;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** HTTPS-only structured model adapter. It exposes no tools, files, database or trading APIs. */
@Service
public class HttpStructuredAgentModelGateway implements StructuredAgentModelGateway {
    private static final MediaType JSON = MediaType.get("application/json");
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final AgentModelProperties properties;
    private final AgentEvalAdmissionGate evalGate;

    public HttpStructuredAgentModelGateway(OkHttpClient client, ObjectMapper mapper,
                                           AgentModelProperties properties,
                                           AgentEvalAdmissionGate evalGate) {
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(Math.max(1_000, properties.getCallTimeoutMs()), TimeUnit.MILLISECONDS)
                .build();
        this.mapper = mapper;
        this.properties = properties;
        this.evalGate = evalGate;
    }

    @Override
    public StructuredModelResult generate(StructuredModelRequest request) {
        validateConfiguration();
        String evalSha = evalGate.requirePassed(properties.getModelVersion());
        HttpUrl endpoint = HttpUrl.get(properties.getEndpoint());
        Map<String, Object> envelope = Map.of(
                "schemaVersion", "agent-structured-model-request-v1",
                "task", request.task(),
                "modelVersion", properties.getModelVersion(),
                "promptVersion", properties.getPromptVersion(),
                "input", request.input(),
                "outputSchema", request.outputSchema(),
                "tools", java.util.List.of());
        byte[] requestBytes = write(envelope);
        String requestSha = sha256(requestBytes);
        Request.Builder http = new Request.Builder().url(endpoint)
                .post(RequestBody.create(requestBytes, JSON));
        if (properties.getBearerToken() != null && !properties.getBearerToken().isBlank()) {
            http.header("Authorization", "Bearer " + properties.getBearerToken().trim());
        }
        long started = System.nanoTime();
        try (Response response = client.newCall(http.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Agent model endpoint returned HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IllegalStateException("Agent model response body is missing");
            long declared = body.contentLength();
            int max = properties.getMaxResponseBytes();
            if (max < 1_024 || declared > max) {
                throw new IllegalStateException("Agent model response exceeds configured limit");
            }
            byte[] responseBytes = body.bytes();
            if (responseBytes.length > max) {
                throw new IllegalStateException("Agent model response exceeds configured limit");
            }
            JsonNode root = mapper.readTree(responseBytes);
            if (!"agent-structured-model-response-v1".equals(root.path("schemaVersion").asText())
                    || !properties.getModelVersion().equals(root.path("modelVersion").asText())
                    || !root.has("output") || !root.path("output").isObject()) {
                throw new IllegalStateException("Agent model returned an invalid structured envelope");
            }
            long cost = root.path("costMicrousd").asLong(-1);
            if (cost < 0) throw new IllegalStateException("Agent model cost evidence is required");
            JsonNode output = root.path("output");
            return new StructuredModelResult(output, properties.getModelVersion(),
                    properties.getPromptVersion(), requestSha, sha256(responseBytes),
                    sha256(write(output)), evalSha,
                    Math.max(0, (System.nanoTime() - started) / 1_000_000), cost);
        } catch (IOException error) {
            throw new IllegalStateException("Agent model call failed closed", error);
        }
    }

    private void validateConfiguration() {
        if (!properties.isEnabled()) throw new IllegalStateException("Agent model gateway is disabled");
        HttpUrl endpoint;
        try {
            endpoint = HttpUrl.get(properties.getEndpoint());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Agent model endpoint is invalid", invalid);
        }
        String host = endpoint.host().toLowerCase(Locale.ROOT);
        boolean allowed = properties.getAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).anyMatch(host::equals);
        if (!endpoint.isHttps() || !allowed || !endpoint.queryParameterNames().isEmpty()
                || !endpoint.username().isEmpty() || !endpoint.password().isEmpty()) {
            throw new IllegalStateException("Agent model endpoint violates HTTPS allowlist policy");
        }
        if (properties.getModelVersion() == null || properties.getModelVersion().isBlank()
                || properties.getPromptVersion() == null || properties.getPromptVersion().isBlank()
                || properties.getBearerToken() == null || properties.getBearerToken().isBlank()) {
            throw new IllegalStateException("Agent model version, prompt version and token are required");
        }
    }

    private byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException error) {
            throw new IllegalArgumentException("Agent model envelope is not serializable", error);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
