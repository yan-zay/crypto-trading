package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.data.InMemoryHistoricalDataProvider;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestEngineTest {

    private BacktestEngine engine;
    private Instrument btcUsdt;
    private BacktestConfig config;

    @BeforeEach
    void setUp() {
        PerformanceCalculator performanceCalculator = new PerformanceCalculator();
        List<FactorCalculator> factorCalculators = List.of();
        engine = new BacktestEngine(performanceCalculator, factorCalculators);

        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

        long startTime = 1_700_000_000_000L;
        long endTime = startTime + 10 * 60_000L;
        config = new BacktestConfig(btcUsdt, Timeframe.M1, startTime, endTime, BigDecimal.valueOf(10000));
    }

    /**
     * 创建测试用 BarEvent 列表。
     * 价格序列: 100, 95, 90, 95, 100, 105, 100, 95, 90, 100
     * 首根 bar 跳过（策略不产生信号），后续 bar 触发买卖。
     */
    private List<BarEvent> createTestBars() {
        BigDecimal[] prices = {
                BigDecimal.valueOf(100), BigDecimal.valueOf(95), BigDecimal.valueOf(90),
                BigDecimal.valueOf(95), BigDecimal.valueOf(100), BigDecimal.valueOf(105),
                BigDecimal.valueOf(100), BigDecimal.valueOf(95), BigDecimal.valueOf(90),
                BigDecimal.valueOf(100)
        };

        long baseTime = config.startTime();
        List<BarEvent> bars = new ArrayList<>();

        for (int i = 0; i < prices.length; i++) {
            long ts = baseTime + i * 60_000L;
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, ts);
            bars.add(new BarEvent(
                    btcUsdt, metadata, Timeframe.M1,
                    prices[i], prices[i], prices[i], prices[i],
                    BigDecimal.valueOf(1000), BigDecimal.valueOf(100000),
                    true
            ));
        }
        return bars;
    }

    /**
     * 简单的低买高卖策略，用于测试。
     * 跟踪前一根 bar 的收盘价，当价格下跌超过阈值时买入，上涨超过阈值时卖出。
     */
    private static class BuyLowSellHighStrategy implements Strategy {

        private static final BigDecimal THRESHOLD = BigDecimal.valueOf(0.03);
        private BigDecimal previousClose = null;

        @Override
        public String name() {
            return "BuyLowSellHigh";
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

            BigDecimal currentClose = bar.close();

            if (previousClose == null) {
                previousClose = currentClose;
                return null;
            }

            BigDecimal change = currentClose.subtract(previousClose)
                    .divide(previousClose, 6, java.math.RoundingMode.HALF_UP);
            previousClose = currentClose;

            if (change.compareTo(THRESHOLD.negate()) <= 0) {
                return new SignalEvent(
                        name(), bar.instrument(), SignalType.BUY,
                        BigDecimal.ONE, "Price dropped " + change.multiply(BigDecimal.valueOf(100)) + "%",
                        Map.of("change", change), bar.metadata().exchangeTimestamp()
                );
            }

            if (change.compareTo(THRESHOLD) >= 0) {
                return new SignalEvent(
                        name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.ONE, "Price rose " + change.multiply(BigDecimal.valueOf(100)) + "%",
                        Map.of("change", change), bar.metadata().exchangeTimestamp()
                );
            }

            return null;
        }
    }

    @Test
    @DisplayName("回测结果不应为空")
    void shouldReturnNonNullResult() {
        List<BarEvent> bars = createTestBars();
        HistoricalDataProvider dataProvider = new InMemoryHistoricalDataProvider(bars);
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, dataProvider);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("应收集到交易信号")
    void shouldCollectSignals() {
        List<BarEvent> bars = createTestBars();
        HistoricalDataProvider dataProvider = new InMemoryHistoricalDataProvider(bars);
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, dataProvider);

        assertThat(result.signals()).isNotEmpty();
    }

    @Test
    @DisplayName("应存在交易记录")
    void shouldHaveTrades() {
        List<BarEvent> bars = createTestBars();
        HistoricalDataProvider dataProvider = new InMemoryHistoricalDataProvider(bars);
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, dataProvider);

        assertThat(result.trades()).isNotEmpty();
    }

    @Test
    @DisplayName("应生成性能报告")
    void shouldGeneratePerformanceReport() {
        List<BarEvent> bars = createTestBars();
        HistoricalDataProvider dataProvider = new InMemoryHistoricalDataProvider(bars);
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, dataProvider);

        PerformanceReport report = result.performanceReport();
        assertThat(report).isNotNull();
        assertThat(report.initialBalance()).isEqualByComparingTo(config.initialBalance());
        assertThat(report.startTime()).isEqualTo(config.startTime());
        assertThat(report.endTime()).isEqualTo(config.endTime());
    }

    @Test
    @DisplayName("最终余额不应为零")
    void shouldHaveNonZeroFinalBalance() {
        List<BarEvent> bars = createTestBars();
        HistoricalDataProvider dataProvider = new InMemoryHistoricalDataProvider(bars);
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, dataProvider);

        assertThat(result.finalBalance()).isNotNull();
        assertThat(result.finalBalance().compareTo(BigDecimal.ZERO)).isGreaterThan(0);
    }

    @Test
    @DisplayName("信号应包含 BUY 和 SELL 类型")
    void shouldContainBuyAndSellSignals() {
        List<BarEvent> bars = createTestBars();
        HistoricalDataProvider dataProvider = new InMemoryHistoricalDataProvider(bars);
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, dataProvider);

        boolean hasBuy = result.signals().stream()
                .anyMatch(s -> s.type() == SignalType.BUY);
        boolean hasSell = result.signals().stream()
                .anyMatch(s -> s.type() == SignalType.SELL);

        assertThat(hasBuy).isTrue();
        assertThat(hasSell).isTrue();
    }

    @Test
    @DisplayName("交易数不应超过 BUY 信号数")
    void shouldNotExceedBuySignalCount() {
        List<BarEvent> bars = createTestBars();
        HistoricalDataProvider dataProvider = new InMemoryHistoricalDataProvider(bars);
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, dataProvider);

        long buySignalCount = result.signals().stream()
                .filter(s -> s.type() == SignalType.BUY)
                .count();

        // Not all BUY signals execute (position already exists or insufficient balance)
        assertThat(result.trades().size()).isLessThanOrEqualTo((int) buySignalCount);
        assertThat(result.trades().size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("性能报告应包含交易统计")
    void shouldReportTradeStatistics() {
        List<BarEvent> bars = createTestBars();
        HistoricalDataProvider dataProvider = new InMemoryHistoricalDataProvider(bars);
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, dataProvider);

        PerformanceReport report = result.performanceReport();
        assertThat(report.totalTrades()).isGreaterThan(0);
        assertThat(report.totalTrades())
                .isEqualTo(report.winningTrades() + report.losingTrades());
    }

    @Test
    @DisplayName("无数据时应返回零交易结果")
    void shouldReturnZeroTradesWhenNoData() {
        HistoricalDataProvider emptyProvider = new InMemoryHistoricalDataProvider(List.of());
        Strategy strategy = new BuyLowSellHighStrategy();

        BacktestResult result = engine.run(config, strategy, emptyProvider);

        assertThat(result).isNotNull();
        assertThat(result.signals()).isEmpty();
        assertThat(result.trades()).isEmpty();
        assertThat(result.finalBalance()).isEqualByComparingTo(config.initialBalance());
    }
}
