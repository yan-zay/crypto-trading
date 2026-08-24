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

class PortfolioBudgetAnalyzerTest {
    private static final InstrumentId BTC = new InstrumentId(Exchange.BINANCE,
            MarketType.PERPETUAL, "BTCUSDT");
    private static final InstrumentId ETH = new InstrumentId(Exchange.BINANCE,
            MarketType.PERPETUAL, "ETHUSDT");
    private final PortfolioBudgetAnalyzer analyzer = new PortfolioBudgetAnalyzer();

    @Test
    void aggregatesAccountStrategyInstrumentAndDirectionBudgets() {
        PortfolioExposure btcLong = exposure("alpha", BTC, ExposureDirection.LONG, "2", "100");
        PortfolioExposure ethShort = exposure("beta", ETH, ExposureDirection.SHORT, "5", "20");
        Map<PortfolioBudgetKey, BigDecimal> limits = Map.of(
                PortfolioBudgetKey.account("paper-1"), bd("250"),
                PortfolioBudgetKey.strategy("paper-1", "alpha"), bd("300"),
                PortfolioBudgetKey.strategy("paper-1", "beta"), bd("100"),
                PortfolioBudgetKey.instrument("paper-1", BTC), bd("200"),
                PortfolioBudgetKey.instrument("paper-1", ETH), bd("80"),
                PortfolioBudgetKey.direction("paper-1", ExposureDirection.LONG), bd("200"),
                PortfolioBudgetKey.direction("paper-1", ExposureDirection.SHORT), bd("120"));

        PortfolioBudgetView view = analyzer.analyze(snapshot(List.of(btcLong, ethShort)), limits);

        assertThat(view.totalGrossNotional()).isEqualByComparingTo("300");
        assertThat(view.totalLongNotional()).isEqualByComparingTo("200");
        assertThat(view.totalShortNotional()).isEqualByComparingTo("100");
        assertThat(view.anyBreached()).isTrue();
        assertThat(view.usages().get(PortfolioBudgetKey.account("paper-1")))
                .satisfies(usage -> {
                    assertThat(usage.usedNotional()).isEqualByComparingTo("300");
                    assertThat(usage.remainingNotional()).isEqualByComparingTo("-50");
                    assertThat(usage.utilization()).isEqualByComparingTo("1.2");
                    assertThat(usage.breached()).isTrue();
                });
        assertThat(view.usages().get(PortfolioBudgetKey.instrument("paper-1", ETH)).breached())
                .isTrue();
        assertThat(view.usages().get(PortfolioBudgetKey.strategy("paper-1", "alpha")).breached())
                .isFalse();
    }

    @Test
    void requiresEveryObservedDimensionToHaveAnExplicitBudget() {
        PortfolioExposure exposure = exposure("alpha", BTC, ExposureDirection.LONG, "1", "100");
        Map<PortfolioBudgetKey, BigDecimal> incomplete = Map.of(
                PortfolioBudgetKey.account("paper-1"), bd("1000"));

        assertThatThrownBy(() -> analyzer.analyze(snapshot(List.of(exposure)), incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing configured budgets")
                .hasMessageContaining("STRATEGY:paper-1:alpha")
                .hasMessageContaining("INSTRUMENT:paper-1:binance:perpetual:BTCUSDT")
                .hasMessageContaining("DIRECTION:paper-1:LONG");
    }

    @Test
    void exactLimitIsNotReportedAsABreachAndReportsAreImmutable() {
        PortfolioExposure exposure = exposure("alpha", BTC, ExposureDirection.LONG, "1", "100");
        Map<PortfolioBudgetKey, BigDecimal> limits = completeLimits("100");

        PortfolioBudgetView view = analyzer.analyze(snapshot(List.of(exposure)), limits);

        assertThat(view.anyBreached()).isFalse();
        assertThat(view.usages().values()).allSatisfy(usage -> {
            assertThat(usage.utilization()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(usage.remainingNotional()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        assertThatThrownBy(() -> view.usages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesExactNotionalAndRejectsIncompleteSnapshots() {
        PortfolioExposure huge = exposure("alpha", BTC, ExposureDirection.LONG,
                "10000000000000000000000000000000001", "1");
        Map<PortfolioBudgetKey, BigDecimal> limits = completeLimits("10000000000000000000000000000000000");
        assertThat(analyzer.analyze(snapshot(List.of(huge)), limits).anyBreached()).isTrue();

        PortfolioExposureSnapshot incomplete = PortfolioExposureSnapshot.create("snap", 1_000,
                "USDT", java.util.Set.of("paper-1"), SnapshotCompleteness.INCOMPLETE, List.of());
        assertThatThrownBy(() -> analyzer.analyze(incomplete,
                Map.of(PortfolioBudgetKey.account("paper-1"), BigDecimal.ONE)))
                .hasMessageContaining("incomplete");
    }

    private Map<PortfolioBudgetKey, BigDecimal> completeLimits(String value) {
        BigDecimal limit = bd(value);
        return Map.of(
                PortfolioBudgetKey.account("paper-1"), limit,
                PortfolioBudgetKey.strategy("paper-1", "alpha"), limit,
                PortfolioBudgetKey.instrument("paper-1", BTC), limit,
                PortfolioBudgetKey.direction("paper-1", ExposureDirection.LONG), limit);
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

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
