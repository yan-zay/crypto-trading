package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.InstrumentId;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure, side-effect-free linear price-shock evaluator. */
public final class DeterministicStressAnalyzer {
    private static final MathContext MC = MathContext.DECIMAL128;

    public PortfolioStressReport analyze(BigDecimal initialEquity,
                                         PortfolioExposureSnapshot snapshot,
                                         List<StressScenario> scenarios) {
        throw new IllegalStateException("point-in-time equity snapshot is required; legacy input is NOT_VERIFIED");
    }

    public PortfolioStressReport analyze(PortfolioEquitySnapshot equitySnapshot,
                                         PortfolioExposureSnapshot snapshot,
                                         List<StressScenario> scenarios) {
        Objects.requireNonNull(equitySnapshot, "equitySnapshot").requireComplete();
        BigDecimal initialEquity = equitySnapshot.equity();
        Objects.requireNonNull(snapshot, "snapshot").requireComplete();
        if (snapshot.accountIds().size() != 1) {
            throw new IllegalArgumentException("stress analysis requires exactly one account equity scope");
        }
        if (!snapshot.accountIds().contains(equitySnapshot.accountId())
                || snapshot.asOfTimeMs() != equitySnapshot.asOfTimeMs()
                || !snapshot.valuationCurrency().equals(equitySnapshot.valuationCurrency())) {
            throw new IllegalArgumentException("equity and exposure snapshots must share account, currency and PIT time");
        }
        Objects.requireNonNull(scenarios, "scenarios");
        if (scenarios.isEmpty()) throw new IllegalArgumentException("at least one stress scenario is required");
        List<PortfolioExposure> safeExposures = snapshot.exposures();
        List<StressScenario> orderedScenarios = scenarios.stream()
                .peek(value -> Objects.requireNonNull(value, "stress scenario"))
                .sorted(Comparator.comparing(StressScenario::scenarioId))
                .toList();
        if (orderedScenarios.stream().map(StressScenario::scenarioId).distinct().count()
                != orderedScenarios.size()) {
            throw new IllegalArgumentException("stress scenario ids must be unique");
        }

        List<StressScenarioResult> results = new ArrayList<>();
        for (StressScenario scenario : orderedScenarios) {
            results.add(evaluate(initialEquity, safeExposures, scenario));
        }
        StressScenarioResult worst = results.stream()
                .max(Comparator.comparing(StressScenarioResult::loss)
                        .thenComparing(StressScenarioResult::scenarioId, Comparator.reverseOrder()))
                .orElseThrow();
        return new PortfolioStressReport(snapshot.snapshotId(), snapshot.asOfTimeMs(),
                snapshot.valuationCurrency(), snapshot.sourceManifestSha256(),
                equitySnapshot.ledgerManifestSha256(), equitySnapshot.contentSha256(),
                AnalyticsUse.RESEARCH_ONLY, initialEquity.stripTrailingZeros(), results, worst);
    }

    private StressScenarioResult evaluate(BigDecimal initialEquity,
                                          List<PortfolioExposure> exposures,
                                          StressScenario scenario) {
        Map<String, BigDecimal> byStrategy = new TreeMap<>();
        Map<String, InstrumentContribution> byInstrument = new TreeMap<>();
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (PortfolioExposure exposure : exposures) {
            Objects.requireNonNull(exposure, "exposure");
            BigDecimal shock = scenario.returnShocks().get(exposure.instrumentId());
            if (shock == null) {
                throw new IllegalArgumentException("scenario " + scenario.scenarioId()
                        + " is missing shock for " + exposure.instrumentId().value());
            }
            BigDecimal pnl = exposure.signedNotional().multiply(shock);
            totalPnl = totalPnl.add(pnl);
            byStrategy.merge(exposure.strategyId(), pnl, BigDecimal::add);
            byInstrument.compute(exposure.instrumentId().value(), (ignored, current) -> {
                BigDecimal sum = current == null ? pnl : current.pnl().add(pnl);
                return new InstrumentContribution(exposure.instrumentId(), sum);
            });
        }
        BigDecimal stressedEquity = initialEquity.add(totalPnl);
        BigDecimal loss = totalPnl.signum() < 0 ? totalPnl.negate() : BigDecimal.ZERO;
        BigDecimal lossFraction = loss.divide(initialEquity, MC);
        LinkedHashMap<InstrumentId, BigDecimal> instrumentOutput = new LinkedHashMap<>();
        byInstrument.values().forEach(value -> instrumentOutput.put(value.instrumentId(), value.pnl()));
        return new StressScenarioResult(scenario.scenarioId(), totalPnl, stressedEquity,
                loss, lossFraction, new LinkedHashMap<>(byStrategy), instrumentOutput);
    }

    private record InstrumentContribution(InstrumentId instrumentId, BigDecimal pnl) {
    }
}
