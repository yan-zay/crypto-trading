package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Named deterministic instrument-return shock; -1 means a complete price loss. */
public record StressScenario(String scenarioId, Map<InstrumentId, BigDecimal> returnShocks) {
    public StressScenario {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId is required");
        }
        scenarioId = scenarioId.trim();
        Objects.requireNonNull(returnShocks, "returnShocks");
        if (returnShocks.isEmpty()) throw new IllegalArgumentException("returnShocks must not be empty");
        TreeMap<String, Map.Entry<InstrumentId, BigDecimal>> sorted = new TreeMap<>();
        for (Map.Entry<InstrumentId, BigDecimal> entry : returnShocks.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "shock instrumentId");
            BigDecimal shock = Objects.requireNonNull(entry.getValue(), "return shock");
            if (shock.compareTo(BigDecimal.ONE.negate()) < 0) {
                throw new IllegalArgumentException("return shock cannot be below -100%");
            }
            sorted.put(entry.getKey().value(), Map.entry(entry.getKey(), shock.stripTrailingZeros()));
        }
        LinkedHashMap<InstrumentId, BigDecimal> copy = new LinkedHashMap<>();
        sorted.values().forEach(entry -> copy.put(entry.getKey(), entry.getValue()));
        returnShocks = Collections.unmodifiableMap(copy);
    }
}
