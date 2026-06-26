package com.tj.crypto.backtest.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.report.PerformanceCalculator;
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
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.impl.MacdCrossStrategy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 回测运行工具类。
 * 简化回测环境的组装，无需 Spring 上下文即可运行完整回测。
 *
 * 典型用法：
 * <pre>
 *   BacktestResult result = BacktestRunner.runBacktest("BTCUSDT", "1m", 30, 100000);
 * </pre>
 */
@Slf4j
public final class BacktestRunner {

    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final int MACD_WARMUP_BARS = 40;

    private BacktestRunner() {}

    /**
     * 运行回测。
     *
     * @param symbol         交易对符号，如 "BTCUSDT"
     * @param timeframeCode  时间周期代码，如 "1m", "5m", "1h"
     * @param daysBack       回测天数（不含预热期）
     * @param initialBalance 初始资金
     * @return 回测结果
     */
    public static BacktestResult runBacktest(String symbol, String timeframeCode,
                                              int daysBack, double initialBalance) {
        return runBacktest(symbol, timeframeCode, daysBack, initialBalance, new FactorProperties());
    }

    /**
     * 运行回测（支持自定义因子参数）。
     *
     * @param symbol           交易对符号，如 "BTCUSDT"
     * @param timeframeCode    时间周期代码，如 "1m", "5m", "1h"
     * @param daysBack         回测天数（不含预热期）
     * @param initialBalance   初始资金
     * @param factorProperties 因子参数配置（可自定义 MACD 等参数）
     * @return 回测结果
     */
    public static BacktestResult runBacktest(String symbol, String timeframeCode,
                                              int daysBack, double initialBalance,
                                              FactorProperties factorProperties) {
        // 1. 解析参数
        Timeframe timeframe = Timeframe.fromCode(timeframeCode);
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, symbol);

        // 2. 计算时间范围（含 MACD 预热期）
        long now = System.currentTimeMillis();
        long warmupMillis = MACD_WARMUP_BARS * timeframe.getMillis();
        long backtestMillis = (long) daysBack * MILLIS_PER_DAY;
        long startTime = now - backtestMillis - warmupMillis;
        long endTime = now - MILLIS_PER_DAY; // 避免最后一根未完成的 bar

        // 3. 组装依赖
        OkHttpClient httpClient = createHttpClient();
        BinanceHistoricalDataProperties properties = new BinanceHistoricalDataProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        BinanceHistoricalDataProvider dataProvider =
                new BinanceHistoricalDataProvider(httpClient, properties, objectMapper);

        // 4. 创建共享 BarCache（因子计算器和回测引擎共用）
        InMemoryEventBus sharedEventBus = new InMemoryEventBus();
        InMemoryBarCache sharedBarCache = new InMemoryBarCache(sharedEventBus);

        // 5. 创建因子计算器（MacdFactor 引用 sharedBarCache）
        MacdFactor macdFactor = new MacdFactor(sharedBarCache, factorProperties);
        List<FactorCalculator> factorCalculators = List.of(macdFactor);

        // 6. 创建执行引擎（含风控 + 仓位 + 滑点）
        PerformanceCalculator performanceCalculator = new PerformanceCalculator();
        RiskProperties riskProperties = new RiskProperties();
        ExecutionEngine executionEngine = new ExecutionEngine(
                new RiskEngine(List.of()),
                new PositionSizer(),
                new FixedSlippageModel(riskProperties));

        // 7. 创建回测引擎
        BacktestEngine engine = new BacktestEngine(performanceCalculator, factorCalculators, executionEngine);

        // 8. 创建策略
        Strategy strategy = new MacdCrossStrategy();

        // 9. 构建回测配置
        BigDecimal balance = BigDecimal.valueOf(initialBalance);
        BacktestConfig config = new BacktestConfig(instrument, timeframe, startTime, endTime, balance);

        log.info("Running backtest: {} {} for {} days (warmup {} bars), initial balance=${}",
                symbol, timeframeCode, daysBack, MACD_WARMUP_BARS, initialBalance);

        // 10. 运行回测（使用共享 BarCache）
        BacktestResult result = engine.run(config, strategy, dataProvider, sharedBarCache);

        log.info("Backtest finished: final balance=${}", result.finalBalance());
        return result;
    }

    /**
     * 使用外部数据提供者运行回测（支持自定义因子参数）。
     * 当需要对同一数据集运行多组参数时使用，避免重复获取数据。
     *
     * @param instrument       交易工具
     * @param timeframe        时间周期
     * @param startTime        回测起始时间（毫秒）
     * @param endTime          回测结束时间（毫秒）
     * @param initialBalance   初始资金
     * @param factorProperties 因子参数配置
     * @param dataProvider     历史数据提供者（可复用）
     * @return 回测结果
     */
    public static BacktestResult runWithProvider(Instrument instrument, Timeframe timeframe,
                                                  long startTime, long endTime,
                                                  double initialBalance,
                                                  FactorProperties factorProperties,
                                                  HistoricalDataProvider dataProvider) {
        // 1. 创建共享 BarCache（因子计算器和回测引擎共用）
        InMemoryEventBus sharedEventBus = new InMemoryEventBus();
        InMemoryBarCache sharedBarCache = new InMemoryBarCache(sharedEventBus);

        // 2. 创建因子计算器
        MacdFactor macdFactor = new MacdFactor(sharedBarCache, factorProperties);
        List<FactorCalculator> factorCalculators = List.of(macdFactor);

        // 3. 创建执行引擎（含风控 + 仓位 + 滑点）
        PerformanceCalculator performanceCalculator = new PerformanceCalculator();
        RiskProperties riskProperties = new RiskProperties();
        ExecutionEngine executionEngine = new ExecutionEngine(
                new RiskEngine(List.of()),
                new PositionSizer(),
                new FixedSlippageModel(riskProperties));

        // 4. 创建回测引擎
        BacktestEngine engine = new BacktestEngine(performanceCalculator, factorCalculators, executionEngine);

        // 5. 创建策略
        Strategy strategy = new MacdCrossStrategy();

        // 6. 构建回测配置
        BigDecimal balance = BigDecimal.valueOf(initialBalance);
        BacktestConfig config = new BacktestConfig(instrument, timeframe, startTime, endTime, balance);

        // 7. 运行回测（使用共享 BarCache）
        return engine.run(config, strategy, dataProvider, sharedBarCache);
    }

    /**
     * 创建 Binance 历史数据提供者。
     * 使用 SOCKS 代理访问 Binance API。
     *
     * @return BinanceHistoricalDataProvider 实例
     */
    public static BinanceHistoricalDataProvider createDataProvider() {
        OkHttpClient httpClient = createHttpClient();
        BinanceHistoricalDataProperties properties = new BinanceHistoricalDataProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        return new BinanceHistoricalDataProvider(httpClient, properties, objectMapper);
    }

    /**
     * 计算回测时间范围（含 MACD 预热期）。
     *
     * @param timeframeCode 时间周期代码
     * @param daysBack      回测天数
     * @return long[]{startTime, endTime}
     */
    public static long[] calculateTimeRange(String timeframeCode, int daysBack) {
        Timeframe timeframe = Timeframe.fromCode(timeframeCode);
        long now = System.currentTimeMillis();
        long warmupMillis = MACD_WARMUP_BARS * timeframe.getMillis();
        long backtestMillis = (long) daysBack * MILLIS_PER_DAY;
        long startTime = now - backtestMillis - warmupMillis;
        long endTime = now - MILLIS_PER_DAY;
        return new long[]{startTime, endTime};
    }

    /**
     * 创建带 SOCKS 代理的 OkHttpClient。
     * 代理地址从环境变量 PROXY_HOST / PROXY_PORT 读取，默认 127.0.0.1:10808。
     */
    static OkHttpClient createHttpClient() {
        String proxyHost = System.getenv().getOrDefault("PROXY_HOST", "127.0.0.1");
        int proxyPort = Integer.parseInt(System.getenv().getOrDefault("PROXY_PORT", "10808"));

        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));

        return new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(300, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}
