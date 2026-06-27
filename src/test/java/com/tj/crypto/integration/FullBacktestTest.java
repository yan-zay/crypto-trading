package com.tj.crypto.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.tj.crypto.config.properties.BinanceHistoricalDataProperties;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.execution.FixedSlippageModel;
import com.tj.crypto.factor.FactorProperties;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.factor.technical.MacdFactor;
import com.tj.crypto.marketdata.backfill.BinanceHistoricalDataProvider;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.impl.MacdCrossStrategy;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 Binance 真实历史数据的完整回测集成测试。
 * 验证：数据获取 → MACD 因子计算 → 策略信号 → 执行引擎 → 性能报告 全链路。
 *
 * 注意：此测试需要网络连接访问 Binance API。
 * 使用 7 天 1min 数据（约 10080 根 K 线），避免内存问题。
 */
class FullBacktestTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final String TIMEFRAME_CODE = "1m";
    private static final int DAYS_BACK = 7;
    private static final double INITIAL_BALANCE = 100_000.0;
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final int MACD_WARMUP_BARS = 40;

    private static BacktestResult cachedResult;

    @BeforeAll
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    static void runBacktestOnce() {
        // 1. 解析参数
        Timeframe timeframe = Timeframe.fromCode(TIMEFRAME_CODE);
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, SYMBOL);

        // 2. 计算时间范围（含 MACD 预热期）
        long now = System.currentTimeMillis();
        long warmupMillis = MACD_WARMUP_BARS * timeframe.getMillis();
        long backtestMillis = (long) DAYS_BACK * MILLIS_PER_DAY;
        long startTime = now - backtestMillis - warmupMillis;
        long endTime = now - MILLIS_PER_DAY;

        // 3. 创建数据提供者（使用 SOCKS 代理访问 Binance）
        String proxyHost = System.getenv().getOrDefault("PROXY_HOST", "127.0.0.1");
        int proxyPort = Integer.parseInt(System.getenv().getOrDefault("PROXY_PORT", "10808"));
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(300, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        BinanceHistoricalDataProperties properties = new BinanceHistoricalDataProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        BinanceHistoricalDataProvider dataProvider =
                new BinanceHistoricalDataProvider(httpClient, properties, objectMapper);

        // 4. 创建共享 BarCache（因子和引擎共用）
        InMemoryEventBus sharedEventBus = new InMemoryEventBus();
        InMemoryBarCache sharedBarCache = new InMemoryBarCache(sharedEventBus);

        // 5. 创建因子计算器
        FactorProperties factorProperties = new FactorProperties();
        MacdFactor macdFactor = new MacdFactor(sharedBarCache, factorProperties);
        List<FactorCalculator> factorCalculators = List.of(macdFactor);

        // 6. 创建执行引擎
        PerformanceCalculator performanceCalculator = new PerformanceCalculator();
        RiskProperties riskProperties = new RiskProperties();
        ExecutionEngine executionEngine = new ExecutionEngine(
                new RiskEngine(List.of()),
                new PositionSizer(),
                new FixedSlippageModel(riskProperties),
                new com.tj.crypto.risk.KillSwitch());

        // 7. 创建回测引擎和策略
        BacktestEngine engine = new BacktestEngine(performanceCalculator, factorCalculators, executionEngine);
        Strategy strategy = new MacdCrossStrategy();

        // 8. 构建配置并运行
        BigDecimal balance = BigDecimal.valueOf(INITIAL_BALANCE);
        BacktestConfig config = new BacktestConfig(instrument, timeframe, startTime, endTime, balance);

        System.out.printf("=== Fetching %d days of %s %s data from Binance ===%n", DAYS_BACK, SYMBOL, TIMEFRAME_CODE);
        System.out.printf("Time range: %d .. %d%n", startTime, endTime);

        cachedResult = engine.run(config, strategy, dataProvider, sharedBarCache);

        printReport(cachedResult, config);
    }

    @Test
    @DisplayName("回测完成无异常")
    void shouldCompleteWithoutException() {
        assertThat(cachedResult).isNotNull();
        assertThat(cachedResult.config()).isNotNull();
    }

    @Test
    @DisplayName("至少产生 1 个信号")
    void shouldGenerateAtLeastOneSignal() {
        assertThat(cachedResult.signals())
                .as("MACD strategy should generate at least 1 signal from %d days of %s data",
                        DAYS_BACK, SYMBOL)
                .isNotEmpty();
    }

    @Test
    @DisplayName("性能报告所有字段非 null")
    void shouldHaveNonNullPerformanceReport() {
        PerformanceReport report = cachedResult.performanceReport();
        assertThat(report).isNotNull();
        assertThat(report.totalReturn()).isNotNull();
        assertThat(report.maxDrawdown()).isNotNull();
        assertThat(report.winRate()).isNotNull();
        assertThat(report.avgWin()).isNotNull();
        assertThat(report.avgLoss()).isNotNull();
        assertThat(report.profitFactor()).isNotNull();
        assertThat(report.initialBalance()).isNotNull();
        assertThat(report.finalBalance()).isNotNull();
    }

    @Test
    @DisplayName("总收益率在合理范围（-100% 到 +1000%）")
    void shouldHaveReasonableTotalReturn() {
        BigDecimal totalReturn = cachedResult.performanceReport().totalReturn();
        assertThat(totalReturn)
                .as("Total return should be between -100%% and +1000%%")
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(-100))
                .isLessThanOrEqualTo(BigDecimal.valueOf(1000));
    }

    @Test
    @DisplayName("最大回撤在合理范围（0% 到 100%）")
    void shouldHaveReasonableMaxDrawdown() {
        BigDecimal maxDrawdown = cachedResult.performanceReport().maxDrawdown();
        assertThat(maxDrawdown)
                .as("Max drawdown should be between 0%% and 100%%")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                .isLessThanOrEqualTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("胜率在合理范围（0% 到 100%）")
    void shouldHaveReasonableWinRate() {
        BigDecimal winRate = cachedResult.performanceReport().winRate();
        assertThat(winRate)
                .as("Win rate should be between 0%% and 100%%")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                .isLessThanOrEqualTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("最终余额为正数")
    void shouldHavePositiveFinalBalance() {
        assertThat(cachedResult.finalBalance())
                .as("Final balance should be positive")
                .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("交易价格不为零")
    void shouldHaveNonZeroTradePrices() {
        for (Trade trade : cachedResult.trades()) {
            assertThat(trade.entryPrice())
                    .as("Trade entry price should not be 0: %s", trade)
                    .isNotEqualByComparingTo(BigDecimal.ZERO);
            assertThat(trade.exitPrice())
                    .as("Trade exit price should not be 0: %s", trade)
                    .isNotEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    private static void printReport(BacktestResult result, BacktestConfig config) {
        PerformanceReport report = result.performanceReport();
        System.out.println();
        System.out.println("========================================");
        System.out.println("    Full Backtest Report (Binance)");
        System.out.println("========================================");
        System.out.printf("Symbol:          %s%n", config.instrument().symbol());
        System.out.printf("Timeframe:       %s%n", config.timeframe().getCode());
        System.out.printf("Period:          %d days (+ %d warmup bars)%n", DAYS_BACK, MACD_WARMUP_BARS);
        System.out.printf("Initial Balance: $%s%n", config.initialBalance());
        System.out.printf("Final Balance:   $%s%n", result.finalBalance());
        System.out.println();
        System.out.printf("Total Return:    %s%%%n", report.totalReturn());
        System.out.printf("Max Drawdown:    %s%%%n", report.maxDrawdown());
        System.out.printf("Win Rate:        %s%%%n", report.winRate());
        System.out.printf("Total Trades:    %d%n", report.totalTrades());
        System.out.printf("Winning Trades:  %d%n", report.winningTrades());
        System.out.printf("Losing Trades:   %d%n", report.losingTrades());
        System.out.printf("Avg Win:         $%s%n", report.avgWin());
        System.out.printf("Avg Loss:        $%s%n", report.avgLoss());
        System.out.printf("Profit Factor:   %s%n", report.profitFactor());
        System.out.printf("Max Consec Loss: %d%n", report.maxConsecutiveLosses());
        System.out.println();
        System.out.printf("Signals: %d total%n", result.signals().size());

        // 打印前 10 个信号
        int limit = Math.min(10, result.signals().size());
        for (int i = 0; i < limit; i++) {
            SignalEvent signal = result.signals().get(i);
            System.out.printf("  [%d] %s %s | %s%n",
                    i + 1, signal.type(), signal.instrument().symbol(), signal.reason());
        }
        if (result.signals().size() > limit) {
            System.out.printf("  ... and %d more signals%n", result.signals().size() - limit);
        }

        // 打印前 10 笔交易
        System.out.println();
        System.out.printf("Trades: %d total%n", result.trades().size());
        limit = Math.min(10, result.trades().size());
        for (int i = 0; i < limit; i++) {
            Trade trade = result.trades().get(i);
            System.out.printf("  [%d] %s entry=$%s exit=$%s qty=%s PnL=$%s %s%n",
                    i + 1, trade.instrument().symbol(),
                    trade.entryPrice(), trade.exitPrice(),
                    trade.quantity(), trade.realizedPnL(),
                    trade.isProfitable() ? "WIN" : "LOSS");
        }
        if (result.trades().size() > limit) {
            System.out.printf("  ... and %d more trades%n", result.trades().size() - limit);
        }
        System.out.println("========================================");
    }
}
