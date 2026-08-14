package com.tj.crypto.admin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.domain.ConfigType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Iterator;

/** Type-aware validation for versioned runtime configuration payloads. */
@Component
public class ConfigPayloadValidator {
    private final ObjectMapper objectMapper;

    public ConfigPayloadValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(ConfigType type, String contentJson) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(contentJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Config content is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Config content must be a JSON object");
        }
        switch (type) {
            case RISK -> validateRisk(root);
            case FACTOR -> validateFactor(root);
            case CONNECTOR -> validateConnector(root);
            case STRATEGY -> validateStrategy(root);
            case EXECUTION -> validateExecution(root);
        }
    }

    private void validateRisk(JsonNode root) {
        percentage(root, "maxLossPerTradePct");
        percentage(root, "maxDailyLossPct");
        percentage(root, "maxSizePct");
        integer(root, "slippageBps", 0, 10_000);
    }

    private void validateFactor(JsonNode root) {
        String[] periods = {"smaPeriod", "emaPeriod", "macdFast", "macdSlow", "macdSignal",
                "rsiPeriod", "bbPeriod", "atrPeriod", "adxPeriod"};
        for (String period : periods) integer(root, period, 1, 100_000);
        if (root.has("macdFast") && root.has("macdSlow")
                && root.get("macdFast").asInt() >= root.get("macdSlow").asInt()) {
            throw new IllegalArgumentException("macdFast must be smaller than macdSlow");
        }
        decimal(root, "bbStdDev", BigDecimal.ZERO, BigDecimal.valueOf(100), false);
    }

    private void validateConnector(JsonNode root) {
        arrayOfText(root, "symbols");
        websocketUrl(root, "binanceWsUrl");
        websocketUrl(root, "binanceSpotWsUrl");
        websocketUrl(root, "binancePerpetualWsUrl");
        arrayOfText(root, "binanceTimeframes");
        websocketUrl(root, "coinglassWsUrl");
        websocketUrl(root, "okxWsUrl");
        httpUrl(root, "okxRestBaseUrl");
        booleanValue(root, "okxEnabled");
        arrayOfText(root, "okxInstruments");
        arrayOfText(root, "okxTimeframes");
        integer(root, "reconnectIntervalSec", 1, 3600);
    }

    private void validateStrategy(JsonNode root) {
        if (root.has("enabled") && !root.get("enabled").isBoolean()) {
            throw new IllegalArgumentException("enabled must be boolean");
        }
        arrayOfText(root, "symbols");
    }

    private void validateExecution(JsonNode root) {
        integer(root, "slippageBps", 0, 10_000);
    }

    private void percentage(JsonNode root, String name) {
        decimal(root, name, BigDecimal.ZERO, BigDecimal.valueOf(100), true);
    }

    private void decimal(JsonNode root, String name, BigDecimal min, BigDecimal max,
                         boolean allowZero) {
        if (!root.has(name)) return;
        if (!root.get(name).isNumber()) {
            throw new IllegalArgumentException(name + " must be numeric");
        }
        try {
            BigDecimal value = root.get(name).decimalValue();
            if (value.compareTo(min) < 0 || (!allowZero && value.signum() == 0)
                    || value.compareTo(max) > 0) {
                throw new IllegalArgumentException(name + " is outside the supported range");
            }
        } catch (ArithmeticException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(name + " must be numeric", e);
        }
    }

    private void integer(JsonNode root, String name, int min, int max) {
        if (!root.has(name)) return;
        if (!root.get(name).isIntegralNumber() || !root.get(name).canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        int value = root.get(name).asInt();
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
    }

    private void arrayOfText(JsonNode root, String name) {
        if (!root.has(name)) return;
        JsonNode value = root.get(name);
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be a non-empty array");
        }
        Iterator<JsonNode> elements = value.elements();
        while (elements.hasNext()) {
            if (!elements.next().isTextual()) {
                throw new IllegalArgumentException(name + " must contain strings only");
            }
        }
    }

    private void websocketUrl(JsonNode root, String name) {
        if (!root.has(name)) return;
        try {
            URI uri = URI.create(root.get(name).asText());
            if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException(name + " must be a ws/wss URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be a valid ws/wss URL", e);
        }
    }

    private void httpUrl(JsonNode root, String name) {
        if (!root.has(name)) return;
        try {
            URI uri = URI.create(root.get(name).asText());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException(name + " must be an http/https URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be a valid http/https URL", e);
        }
    }

    private void booleanValue(JsonNode root, String name) {
        if (root.has(name) && !root.get(name).isBoolean()) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
    }
}
