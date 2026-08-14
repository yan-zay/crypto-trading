package com.tj.crypto.integration;

import com.tj.crypto.backtest.data.CsvHistoricalDataProvider;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.execution.FixedSlippageModel;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backtest verification test.
 * Loads CSV historical data and runs a full backtest with a simple
 * buy-the-dip / sell-the-rally strategy (3% swing threshold).
 *
 * The CSV contains 100 bars of BTCUSDT 1-minute data.
 * Price swings in the dataset are small per bar (~0.27%), so the strategy
 * tracks recent highs and lows and triggers on 3% retracement from those
 * swing points rather than bar-to-bar changes.
 */
class BacktestVerificationTest {

    private static final BigDecimal THREE_PERCENT = BigDecimal.valueOf(0.03);
    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(100_000);

    private BacktestEngine engine;
    private Instrument btcUsdt;
    private BacktestConfig config;
    private CsvHistoricalDataProvider dataProvider;

    @BeforeEach
    void setUp() throws URISyntaxException {
        PerformanceCalculator performanceCalculator = new PerformanceCalculator();
        List<FactorCalculator> factorCalculators = List.of();
        RiskProperties riskProperties = new RiskProperties();
        ExecutionEngine executionEngine = new ExecutionEngine(
                new RiskEngine(List.of()), new PositionSizer(riskProperties), new FixedSlippageModel(riskProperties),
                new com.tj.crypto.risk.KillSwitch());
        engine = new BacktestEngine(performanceCalculator, factorCalculators, executionEngine);

        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

        // CSV covers 1700000000000 .. 1700005940000
        long startTime = 1_700_000_000_000L;
        long endTime = 1_700_005_940_000L;
        config = new BacktestConfig(btcUsdt, Timeframe.M1, startTime, endTime, INITIAL_BALANCE);

        Path csvPath = Paths.get(Objects.requireNonNull(
                getClass().getClassLoader().getResource("backtest/btcusdt_1m_sample.csv")).toURI());
        dataProvider = new CsvHistoricalDataProvider(csvPath, Exchange.BINANCE, MarketType.PERPETUAL);
    }

    /**
     * Swing-based buy-low / sell-high strategy.
     * Tracks recent swing high and swing low. Buys when price drops 3%
     * from the recent swing high (buy-the-dip). Sells when price rises
     * 3% from the recent swing low (sell-the-rally).
     *
     * This approach works with minute-level data where bar-to-bar changes
     * are small (~0.27%) but cumulative swings reach 3%+.
     */
    private static class BuyDipSellRallyStrategy implements Strategy {

        private BigDecimal swingHigh = null;
        private BigDecimal swingLow = null;

        @Override
        public String name() {
            return "BuyDipSellRally";
        }

        @Override
        public Set<Class<? extends MarketEvent>> listenedEvents() {
            return Set.of(BarEvent.class);
        }

        @Override
        public SignalEvent onEvent(MarketEvent event, StrategyContext context) {
            if (!(event instanceof BarEvent bar)) {
                return null;
            }

            BigDecimal close = bar.close();

            // Initialize swing points on first bar
            if (swingHigh == null) {
                swingHigh = close;
                swingLow = close;
                return null;
            }

            // Update swing high / low
            if (close.compareTo(swingHigh) > 0) {
                swingHigh = close;
            }
            if (close.compareTo(swingLow) < 0) {
                swingLow = close;
            }

            // Buy signal: price dropped 3% from recent swing high
            BigDecimal dropFromHigh = swingHigh.subtract(close)
                    .divide(swingHigh, 6, RoundingMode.HALF_UP);
            if (dropFromHigh.compareTo(THREE_PERCENT) >= 0) {
                // Reset swing high after triggering buy (avoid repeated signals)
                swingHigh = close;
                return new SignalEvent(
                        name(), bar.instrument(), SignalType.BUY,
                        BigDecimal.ONE,
                        "Price dropped " + dropFromHigh.multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP) + "% from swing high " + swingHigh,
                        Map.of("dropFromHigh", dropFromHigh),
                        bar.metadata().exchangeTimestamp()
                );
            }

            // Sell signal: price rose 3% from recent swing low
            BigDecimal riseFromLow = close.subtract(swingLow)
                    .divide(swingLow, 6, RoundingMode.HALF_UP);
            if (riseFromLow.compareTo(THREE_PERCENT) >= 0) {
                // Reset swing low after triggering sell (avoid repeated signals)
                swingLow = close;
                return new SignalEvent(
                        name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.ONE,
                        "Price rose " + riseFromLow.multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP) + "% from swing low " + swingLow,
                        Map.of("riseFromLow", riseFromLow),
                        bar.metadata().exchangeTimestamp()
                );
            }

            return null;
        }
    }

    @Test
    @DisplayName("Full backtest verification: CSV data -> strategy -> engine -> report")
    void shouldRunFullBacktestFromCsvData() {
        // --- Act ---
        Strategy strategy = new BuyDipSellRallyStrategy();
        BacktestResult result = engine.run(config, strategy, dataProvider);

        // --- Print results for visibility ---
        System.out.println("=== Backtest Verification Results ===");
        System.out.println("Instrument: " + config.instrument().symbol());
        System.out.println("Timeframe:  " + config.timeframe().getCode());
        System.out.println("Period:     " + config.startTime() + " .. " + config.endTime());
        System.out.println("Initial:    $" + config.initialBalance());
        System.out.println("Final:      $" + result.finalBalance());
        System.out.println();

        System.out.println("--- Signals (" + result.signals().size() + ") ---");
        for (SignalEvent signal : result.signals()) {
            System.out.printf("  %s %s @ %d | %s%n",
                    signal.type(), signal.instrument().symbol(),
                    signal.timestamp(), signal.reason());
        }
        System.out.println();

        System.out.println("--- Trades (" + result.trades().size() + ") ---");
        for (Trade trade : result.trades()) {
            System.out.printf("  %s | entry=$%s exit=$%s qty=%s PnL=$%s%n",
                    trade.instrument().symbol(),
                    trade.entryPrice(), trade.exitPrice(),
                    trade.quantity(), trade.realizedPnL());
        }
        System.out.println();

        PerformanceReport report = result.performanceReport();
        System.out.println("--- Performance Report ---");
        System.out.println(report);
        System.out.printf("  Total Return:     %s%%%n", report.totalReturn());
        System.out.printf("  Max Drawdown:     %s%%%n", report.maxDrawdown());
        System.out.printf("  Win Rate:         %s%%%n", report.winRate());
        System.out.printf("  Total Trades:     %d%n", report.totalTrades());
        System.out.printf("  Winning Trades:   %d%n", report.winningTrades());
        System.out.printf("  Losing Trades:    %d%n", report.losingTrades());
        System.out.printf("  Profit Factor:    %s%n", report.profitFactor());
        System.out.printf("  Max Consec Losses:%d%n", report.maxConsecutiveLosses());
        System.out.println("=== End ===");

        // --- Assert: backtest runs without errors ---
        assertThat(result).isNotNull();

        // --- Assert: at least 1 signal is generated ---
        assertThat(result.signals())
                .as("Strategy should generate at least 1 signal from 100 bars of BTC data")
                .isNotEmpty();

        // --- Assert: performance report is generated ---
        assertThat(report).isNotNull();
        assertThat(report.initialBalance()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(report.startTime()).isEqualTo(config.startTime());
        assertThat(report.endTime()).isEqualTo(config.endTime());

        // --- Assert: no position is closed at price 0 ---
        for (Trade trade : result.trades()) {
            assertThat(trade.exitPrice())
                    .as("Trade exit price should never be 0: %s", trade)
                    .isNotEqualByComparingTo(BigDecimal.ZERO);
            assertThat(trade.entryPrice())
                    .as("Trade entry price should never be 0: %s", trade)
                    .isNotEqualByComparingTo(BigDecimal.ZERO);
        }

        // --- Assert: final balance is positive ---
        assertThat(result.finalBalance())
                .as("Final balance should be positive")
                .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Backtest with no signals produces empty trades and zero PnL")
    void shouldHandleNoSignalsGracefully() {
        // Use a strategy that never signals
        Strategy neverSignalStrategy = new Strategy() {
            @Override
            public String name() { return "NeverSignal"; }

            @Override
            public Set<Class<? extends MarketEvent>> listenedEvents() { return Set.of(BarEvent.class); }

            @Override
            public SignalEvent onEvent(MarketEvent event, StrategyContext context) { return null; }
        };

        BacktestResult result = engine.run(config, neverSignalStrategy, dataProvider);

        assertThat(result).isNotNull();
        assertThat(result.signals()).isEmpty();
        assertThat(result.trades()).isEmpty();
        assertThat(result.finalBalance()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(result.performanceReport()).isNotNull();
        assertThat(result.performanceReport().totalTrades()).isEqualTo(0);
    }
}
