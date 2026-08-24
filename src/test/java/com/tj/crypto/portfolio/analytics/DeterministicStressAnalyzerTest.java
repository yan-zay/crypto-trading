package com.tj.crypto.portfolio.analytics;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.MarketType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicStressAnalyzerTest {
    private static final InstrumentId BTC = new InstrumentId(Exchange.BINANCE,
            MarketType.PERPETUAL, "BTCUSDT");
    private static final InstrumentId ETH = new InstrumentId(Exchange.OKX,
            MarketType.PERPETUAL, "ETHUSDT");
    private final DeterministicStressAnalyzer analyzer = new DeterministicStressAnalyzer();

    @Test
    void appliesSignedShocksAndSelectsWorstScenarioDeterministically() {
        List<PortfolioExposure> exposures = List.of(
                exposure("trend", BTC, ExposureDirection.LONG, "2", "100"),
                exposure("hedge", ETH, ExposureDirection.SHORT, "5", "20"));
        List<StressScenario> scenarios = List.of(
                new StressScenario("rally", Map.of(BTC, bd("0.10"), ETH, bd("0.20"))),
                new StressScenario("crash", Map.of(BTC, bd("-0.30"), ETH, bd("-0.10"))));

        PortfolioStressReport report = analyzer.analyze(equity("USDT", 1_000), snapshot(exposures), scenarios);

        assertThat(report.scenarios()).extracting(StressScenarioResult::scenarioId)
                .containsExactly("crash", "rally");
        StressScenarioResult crash = report.scenarios().get(0);
        assertThat(crash.pnlByStrategy().get("trend")).isEqualByComparingTo("-60");
        assertThat(crash.pnlByStrategy().get("hedge")).isEqualByComparingTo("10");
        assertThat(crash.totalPnl()).isEqualByComparingTo("-50");
        assertThat(crash.stressedEquity()).isEqualByComparingTo("950");
        assertThat(crash.loss()).isEqualByComparingTo("50");
        assertThat(crash.lossFraction()).isEqualByComparingTo("0.05");
        assertThat(report.worstScenario().scenarioId()).isEqualTo("crash");
    }

    @Test
    void equalAndOppositePositionsOffsetForEveryAdmissibleShock() {
        for (int percent = -100; percent <= 300; percent++) {
            BigDecimal shock = BigDecimal.valueOf(percent, 2);
            List<PortfolioExposure> exposures = List.of(
                    exposure("long", BTC, ExposureDirection.LONG, "3", "100"),
                    exposure("short", BTC, ExposureDirection.SHORT, "3", "100"));
            PortfolioStressReport report = analyzer.analyze(equity("USDT", 1_000), snapshot(exposures),
                    List.of(new StressScenario("shock", Map.of(BTC, shock))));
            assertThat(report.worstScenario().totalPnl()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.worstScenario().loss()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void missingShockAndDuplicateScenarioFailClosed() {
        List<PortfolioExposure> exposures = List.of(
                exposure("trend", BTC, ExposureDirection.LONG, "1", "100"));
        assertThatThrownBy(() -> analyzer.analyze(equity("USDT", 1_000), snapshot(exposures),
                List.of(new StressScenario("wrong", Map.of(ETH, bd("-0.2"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing shock");
        StressScenario duplicate = new StressScenario("same", Map.of(BTC, bd("-0.2")));
        assertThatThrownBy(() -> analyzer.analyze(equity("USDT", 1_000), snapshot(exposures),
                List.of(duplicate, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");

        assertThatThrownBy(() -> analyzer.analyze(equity("USD", 1_000), snapshot(exposures),
                List.of(new StressScenario("shock", Map.of(BTC, bd("-0.2"))))))
                .hasMessageContaining("currency and PIT time");
        assertThatThrownBy(() -> analyzer.analyze(bd("1000"), snapshot(exposures),
                List.of(new StressScenario("shock", Map.of(BTC, bd("-0.2"))))))
                .hasMessageContaining("NOT_VERIFIED");
    }

    private PortfolioExposure exposure(String strategy, InstrumentId instrument,
                                       ExposureDirection direction, String quantity, String price) {
        return new PortfolioExposure("paper-1", strategy, instrument, direction,
                bd(quantity), bd(price), BigDecimal.ONE, ExposureValuationModel.LINEAR_QUOTE_SETTLED,
                "USDT", 1_000, 1_000, "e".repeat(64), SnapshotCompleteness.COMPLETE);
    }

    private PortfolioExposureSnapshot snapshot(List<PortfolioExposure> exposures) {
        return PortfolioExposureSnapshot.create("snap", 1_000, "USDT", java.util.Set.of("paper-1"),
                SnapshotCompleteness.COMPLETE, exposures);
    }

    private PortfolioEquitySnapshot equity(String currency, long asOf) {
        return PortfolioEquitySnapshot.create("paper-1", asOf, asOf, currency, bd("1000"),
                "f".repeat(64), SnapshotCompleteness.COMPLETE);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
