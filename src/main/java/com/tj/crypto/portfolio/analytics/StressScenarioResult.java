package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic mark-to-scenario result. Positive PnL is favorable; loss is never negative. */
public record StressScenarioResult(
        String scenarioId,
        BigDecimal totalPnl,
        BigDecimal stressedEquity,
        BigDecimal loss,
        BigDecimal lossFraction,
        Map<String, BigDecimal> pnlByStrategy,
        Map<InstrumentId, BigDecimal> pnlByInstrument
) {
    public StressScenarioResult {
        pnlByStrategy = Collections.unmodifiableMap(new LinkedHashMap<>(pnlByStrategy));
        pnlByInstrument = Collections.unmodifiableMap(new LinkedHashMap<>(pnlByInstrument));
    }
}
