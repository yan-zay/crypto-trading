package com.tj.crypto.integration.soak;

import com.tj.crypto.central.StrategyEngine;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Soak test 运行器。
 * 模拟真实数据流，监控系统在长时间运行下的稳定性。
 *
 * <p>核心能力：
 * <ul>
 *   <li>定时生成 BarEvent，模拟真实行情数据流</li>
 *   <li>周期性采集指标：内存、线程数、延迟、信号数</li>
 *   <li>检测异常：内存泄漏、线程泄漏、事件丢失</li>
 *   <li>输出周期性状态报告</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>
 * SoakTestConfig config = SoakTestConfig.shortTest(symbols);
 * SoakTestRunner runner = new SoakTestRunner(config);
 * SoakTestRunner.SoakTestResult result = runner.run();
 * assertThat(result.anomalies()).isEmpty();
 * </pre>
 */
@Slf4j
public class SoakTestRunner {

    private static final int WARMUP_EVENTS = 50;

    private final SoakTestConfig config;
    private final List<SoakTestMetrics> snapshots = new CopyOnWriteArrayList<>();
    private final List<String> reports = new CopyOnWriteArrayList<>();
    private final List<String> anomalies = new CopyOnWriteArrayList<>();

    private final AtomicLong eventsSent = new AtomicLong(0);
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong totalLatencyNanos = new AtomicLong(0);
    private final AtomicLong maxLatencyNanos = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private volatile int baselineThreadCount;
    private volatile InMemorySignalCollector signalCollector;

    public SoakTestRunner(SoakTestConfig config) {
        this.config = config;
    }

    /**
     * 运行 soak test。
     * 阻塞直到测试完成或被中断。
     *
     * @return 测试结果
     */
    public SoakTestResult run() {
        log.info("=== Soak Test 开始: duration={}min, interval={}ms, symbols={} ===",
                config.durationMinutes(), config.eventIntervalMs(), config.symbols().size());

        // 初始化组件
        InMemoryEventBus eventBus = new InMemoryEventBus();
        this.signalCollector = new InMemorySignalCollector();
        ThreadPoolTaskExecutor threadPool = createThreadPool();
        StrategyContext strategyContext = createStrategyContext();

        StrategyEngine engine = new StrategyEngine(
                threadPool, eventBus, strategyContext, signalCollector,
                createStrategyManager()
        );
        engine.init();

        // 记录基线
        forceGc();
        baselineThreadCount = getThreadCount();

        long durationMs = config.durationMinutes() * 60L * 1000L;
        long checkIntervalMs = config.checkIntervalSeconds() * 1000L;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        CountDownLatch completionLatch = new CountDownLatch(1);

        // 定时发布事件
        ScheduledFuture<?> publisherFuture = scheduler.scheduleAtFixedRate(
                () -> publishEvent(eventBus),
                0, config.eventIntervalMs(), TimeUnit.MILLISECONDS
        );

        // 定时采集指标
        ScheduledFuture<?> metricsFuture = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        captureSnapshot();
                        printReport();
                    } catch (Exception e) {
                        log.error("指标采集异常", e);
                    }
                },
                checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS
        );

        // 等待测试时长结束
        try {
            boolean completed = completionLatch.await(durationMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                // 正常超时结束
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Soak test 被中断");
        } finally {
            publisherFuture.cancel(false);
            metricsFuture.cancel(false);
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            threadPool.shutdown();
        }

        // 最终指标采集
        forceGc();
        captureSnapshot();

        // 检测异常
        detectAnomalies();

        long totalSignals = signalCollector != null ? signalCollector.getAllSignals().size() : 0;
        log.info("=== Soak Test 结束: events={}, signals={}, anomalies={} ===",
                eventsProcessed.get(), totalSignals, anomalies.size());

        return new SoakTestResult(
                new ArrayList<>(snapshots),
                new ArrayList<>(reports),
                new ArrayList<>(anomalies),
                eventsSent.get(),
                eventsProcessed.get(),
                totalSignals
        );
    }

    // =========================================================================
    // 事件发布
    // =========================================================================

    private void publishEvent(InMemoryEventBus eventBus) {
        try {
            long index = eventsSent.getAndIncrement();
            Instrument instrument = config.symbols().get((int) (index % config.symbols().size()));
            long timestamp = System.currentTimeMillis();

            BarEvent bar = createBarEvent(instrument, index, timestamp);
            long startNanos = System.nanoTime();
            eventBus.publish(bar);
            long latencyNanos = System.nanoTime() - startNanos;

            eventsProcessed.incrementAndGet();
            totalLatencyNanos.addAndGet(latencyNanos);
            updateMaxLatency(latencyNanos);
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("事件发布异常", e);
        }
    }

    private void updateMaxLatency(long latencyNanos) {
        long currentMax;
        do {
            currentMax = maxLatencyNanos.get();
        } while (latencyNanos > currentMax && !maxLatencyNanos.compareAndSet(currentMax, latencyNanos));
    }

    // =========================================================================
    // 指标采集
    // =========================================================================

    private void captureSnapshot() {
        long processed = eventsProcessed.get();
        long signals = signalCollector != null ? signalCollector.getAllSignals().size() : 0;
        long errors = errorCount.get();
        long totalLatency = totalLatencyNanos.get();
        double avgLatencyMs = processed > 0
                ? (totalLatency / (double) processed) / 1_000_000.0
                : 0.0;
        double maxLatencyMs = maxLatencyNanos.get() / 1_000_000.0;

        Runtime runtime = Runtime.getRuntime();
        long heapUsedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        int threadCount = getThreadCount();

        SoakTestMetrics snapshot = new SoakTestMetrics(
                processed, signals, avgLatencyMs, maxLatencyMs, heapUsedMB, threadCount, errors
        );
        snapshots.add(snapshot);
    }

    private void printReport() {
        if (snapshots.isEmpty()) return;

        SoakTestMetrics latest = snapshots.get(snapshots.size() - 1);
        long elapsedMs = eventsProcessed.get() * config.eventIntervalMs();
        long totalMs = config.durationMinutes() * 60L * 1000L;
        double progress = totalMs > 0 ? (elapsedMs * 100.0 / totalMs) : 0;

        String report = String.format("[SoakTest %5.1f%%] %s", progress, latest.toReportLine());
        reports.add(report);
        log.info(report);
    }

    // =========================================================================
    // 异常检测
    // =========================================================================

    private void detectAnomalies() {
        detectMemoryLeak();
        detectThreadLeak();
        detectEventLoss();
        detectHighErrorRate();
    }

    /**
     * 检测内存泄漏：最终堆内存是否持续增长超过阈值。
     */
    private void detectMemoryLeak() {
        if (snapshots.size() < 3) return;

        // 跳过前 1/3 的快照（预热期）
        int skip = Math.max(1, snapshots.size() / 3);
        List<SoakTestMetrics> steadyState = snapshots.subList(skip, snapshots.size());

        long firstHeap = steadyState.get(0).heapUsedMB();
        long lastHeap = steadyState.get(steadyState.size() - 1).heapUsedMB();
        long heapGrowth = lastHeap - firstHeap;

        // 阈值：增长超过 100MB 视为异常
        if (heapGrowth > 100) {
            String anomaly = String.format(
                    "MEMORY_LEAK: 堆内存持续增长 %dMB (从 %dMB 到 %dMB)",
                    heapGrowth, firstHeap, lastHeap
            );
            anomalies.add(anomaly);
            log.warn(anomaly);
        }
    }

    /**
     * 检测线程泄漏：线程数是否持续增长。
     */
    private void detectThreadLeak() {
        if (snapshots.isEmpty()) return;

        SoakTestMetrics latest = snapshots.get(snapshots.size() - 1);
        int threadGrowth = latest.threadCount() - baselineThreadCount;

        // 阈值：线程数增长超过 10 视为异常
        if (threadGrowth > 10) {
            String anomaly = String.format(
                    "THREAD_LEAK: 线程数增长 %d (从 %d 到 %d)",
                    threadGrowth, baselineThreadCount, latest.threadCount()
            );
            anomalies.add(anomaly);
            log.warn(anomaly);
        }
    }

    /**
     * 检测事件丢失：已发送事件数与已处理事件数是否一致。
     */
    private void detectEventLoss() {
        long sent = eventsSent.get();
        long processed = eventsProcessed.get();
        long lost = sent - processed;

        if (lost > 0) {
            String anomaly = String.format("EVENT_LOSS: 丢失 %d 个事件 (sent=%d, processed=%d)", lost, sent, processed);
            anomalies.add(anomaly);
            log.warn(anomaly);
        }
    }

    /**
     * 检测高错误率：错误数是否超过已处理事件的 1%。
     */
    private void detectHighErrorRate() {
        long processed = eventsProcessed.get();
        long errors = errorCount.get();

        if (processed > 0 && errors > processed / 100) {
            String anomaly = String.format(
                    "HIGH_ERROR_RATE: 错误率 %.2f%% (errors=%d, processed=%d)",
                    errors * 100.0 / processed, errors, processed
            );
            anomalies.add(anomaly);
            log.warn(anomaly);
        }
    }

    // =========================================================================
    // 工厂方法
    // =========================================================================

    private static ThreadPoolTaskExecutor createThreadPool() {
        int coreCount = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreCount);
        executor.setMaxPoolSize(coreCount * 4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("soak-test-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    private static StrategyContext createStrategyContext() {
        // 返回固定返回 WARMUP 因子的上下文，避免依赖 FactorRegistry
        return new StrategyContext() {
            @Override
            public Factor getFactor(String name, Instrument instrument, Timeframe timeframe) {
                return Factor.warmup(name);
            }

            @Override
            public List<Factor> getAllFactors(Instrument instrument, Timeframe timeframe) {
                return List.of();
            }
        };
    }

    private static StrategyManager createStrategyManager() {
        // 使用简单策略：每 100 根 bar 产生一次信号
        Strategy strategy = new SoakTestStrategy();
        List<Strategy> strategies = List.of(strategy);
        com.tj.crypto.strategy.config.StrategyProperties properties =
                new com.tj.crypto.strategy.config.StrategyProperties();
        return new StrategyManager(strategies, properties);
    }

    private static BarEvent createBarEvent(Instrument instrument, long index, long timestamp) {
        // 生成正弦波价格，模拟真实行情
        double angle = 2 * Math.PI * index / 200.0;
        double basePrice = 50000 + 1000 * Math.sin(angle);
        BigDecimal price = BigDecimal.valueOf(basePrice);
        BigDecimal high = price.add(BigDecimal.valueOf(50));
        BigDecimal low = price.subtract(BigDecimal.valueOf(50));
        BigDecimal volume = BigDecimal.valueOf(1000 + (index % 500));

        return new BarEvent(
                instrument,
                EventMetadata.of(Exchange.BINANCE, timestamp, "soak-" + index),
                Timeframe.M1,
                price, high, low, price,
                volume, volume.multiply(BigDecimal.valueOf(price.doubleValue())),
                true
        );
    }

    private static void forceGc() {
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int getThreadCount() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        return threadMXBean.getThreadCount();
    }

    // =========================================================================
    // 测试策略
    // =========================================================================

    /**
     * Soak test 专用策略。
     * 每 100 根 bar 交替产生 BUY/SELL 信号，用于验证信号管线在长时间运行下的正确性。
     */
    static class SoakTestStrategy implements Strategy {

        private long barCount = 0;
        private boolean holding = false;

        @Override
        public String name() {
            return "SoakTestOscillation";
        }

        @Override
        public Set<Class<? extends MarketEvent>> listenedEvents() {
            return Set.of(BarEvent.class);
        }

        @Override
        public SignalEvent onEvent(MarketEvent event, StrategyContext context) {
            if (!(event instanceof BarEvent bar) || !bar.closed()) {
                return null;
            }

            barCount++;
            if (barCount % 100 != 0) {
                return null;
            }

            if (!holding) {
                holding = true;
                return new SignalEvent(
                        name(), bar.instrument(), SignalType.BUY,
                        BigDecimal.valueOf(0.7), "Soak BUY #" + (barCount / 100),
                        Map.of(), bar.metadata().exchangeTimestamp()
                );
            } else {
                holding = false;
                return new SignalEvent(
                        name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.valueOf(0.7), "Soak SELL #" + (barCount / 100),
                        Map.of(), bar.metadata().exchangeTimestamp()
                );
            }
        }
    }

    // =========================================================================
    // 结果
    // =========================================================================

    /**
     * Soak test 运行结果。
     *
     * @param snapshots        所有指标快照
     * @param reports          周期性状态报告
     * @param anomalies        检测到的异常列表
     * @param totalEventsSent  总发送事件数
     * @param totalEventsProcessed 总处理事件数
     * @param totalSignalsGenerated 总生成信号数
     */
    public record SoakTestResult(
            List<SoakTestMetrics> snapshots,
            List<String> reports,
            List<String> anomalies,
            long totalEventsSent,
            long totalEventsProcessed,
            long totalSignalsGenerated
    ) {
        /**
         * 是否通过（无异常）。
         */
        public boolean passed() {
            return anomalies.isEmpty();
        }
    }
}
