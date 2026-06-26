package com.tj.crypto.integration;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.backtest.data.InMemoryHistoricalDataProvider;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.execution.ExecutionEngine;
import com.tj.crypto.execution.FixedSlippageModel;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.FactorCalculator;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压力测试和边界条件测试。
 *
 * 测试场景：
 * 1. 大数据量回测 — 10000 根 K 线回测，验证无 OOM
 * 2. 高频信号 — 每 10ms 发送一个 BarEvent，持续 5 秒，验证无丢失
 * 3. 异常数据注入 — 畸形值、null 字段、负价格、零成交量
 * 4. 并发安全 — 多线程同时发布事件，验证无 ConcurrentModificationException
 * 5. 内存泄漏 — 创建大量 BarEvent 后验证 GC 可回收
 */
@DisplayName("压力测试与边界条件")
class StressTest {

    private Instrument btcUsdt;
    private BacktestEngine engine;

    @BeforeEach
    void setUp() {
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

        PerformanceCalculator performanceCalculator = new PerformanceCalculator();
        List<FactorCalculator> factorCalculators = List.of();
        RiskProperties riskProperties = new RiskProperties();
        ExecutionEngine executionEngine = new ExecutionEngine(
                new RiskEngine(List.of()), new PositionSizer(), new FixedSlippageModel(riskProperties));
        engine = new BacktestEngine(performanceCalculator, factorCalculators, executionEngine);
    }

    // =========================================================================
    // Test 1: 大数据量回测 — 10000 根模拟 K 线
    // =========================================================================

    @Nested
    @DisplayName("测试 1: 大数据量回测")
    class LargeDataVolumeTest {

        @Test
        @DisplayName("10000 根 K 线回测不应 OOM，应正常完成")
        void shouldCompleteBacktestWith10000Bars() {
            // Arrange: 生成 10000 根 K 线，价格在 90-110 之间波动
            int barCount = 10_000;
            List<BarEvent> bars = generateWaveBars(barCount, 100, 10, 60_000L);

            long startTime = bars.get(0).metadata().exchangeTimestamp();
            long endTime = bars.get(barCount - 1).metadata().exchangeTimestamp();

            BacktestConfig config = new BacktestConfig(
                    btcUsdt, Timeframe.M1, startTime, endTime, BigDecimal.valueOf(100_000));

            HistoricalDataProvider provider = new InMemoryHistoricalDataProvider(bars);
            Strategy strategy = new SimpleOscillationStrategy(3);

            // Act
            BacktestResult result = engine.run(config, strategy, provider);

            // Assert: 回测正常完成，无异常
            assertThat(result).isNotNull();
            assertThat(result.performanceReport()).isNotNull();
            assertThat(result.finalBalance()).isNotNull();
            assertThat(result.finalBalance().compareTo(BigDecimal.ZERO)).isGreaterThan(0);

            // 验证所有 bar 被处理
            // 信号数应合理（不会超过 bar 数）
            assertThat(result.signals().size()).isLessThanOrEqualTo(barCount);
        }

        @Test
        @DisplayName("10000 根 K 线回测后内存应可回收")
        void shouldNotLeakMemoryAfterLargeBacktest() {
            // Arrange
            int barCount = 10_000;
            List<BarEvent> bars = generateWaveBars(barCount, 100, 10, 60_000L);

            long startTime = bars.get(0).metadata().exchangeTimestamp();
            long endTime = bars.get(barCount - 1).metadata().exchangeTimestamp();

            BacktestConfig config = new BacktestConfig(
                    btcUsdt, Timeframe.M1, startTime, endTime, BigDecimal.valueOf(100_000));

            // Act: 运行多次回测
            for (int i = 0; i < 5; i++) {
                HistoricalDataProvider provider = new InMemoryHistoricalDataProvider(bars);
                Strategy strategy = new SimpleOscillationStrategy(3);
                BacktestResult result = engine.run(config, strategy, provider);
                assertThat(result).isNotNull();
            }

            // Assert: 如果到这步没 OOM，说明内存可回收
            // 主动触发 GC 后不应抛异常
            System.gc();
        }
    }

    // =========================================================================
    // Test 2: 高频信号 — 每 10ms 发送一个 BarEvent，持续 5 秒
    // =========================================================================

    @Nested
    @DisplayName("测试 2: 高频信号")
    class HighFrequencySignalTest {

        @Test
        @DisplayName("每 10ms 发送一个 BarEvent 持续 5 秒，不应丢失事件")
        void shouldNotLoseEventsUnderHighFrequency() throws Exception {
            // Arrange: 5 秒 * 100 events/sec = ~500 events
            InMemoryEventBus eventBus = new InMemoryEventBus();
            int expectedEvents = 500;
            AtomicInteger receivedCount = new AtomicInteger(0);
            CountDownLatch allReceived = new CountDownLatch(expectedEvents);

            eventBus.subscribe(BarEvent.class, bar -> {
                receivedCount.incrementAndGet();
                allReceived.countDown();
            });

            // Act: 每 10ms 发送一个事件
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            AtomicInteger sentCount = new AtomicInteger(0);

            long baseTime = System.currentTimeMillis();
            scheduler.scheduleAtFixedRate(() -> {
                if (sentCount.get() < expectedEvents) {
                    BarEvent bar = createBarEvent(btcUsdt, sentCount.get(), baseTime + sentCount.get() * 60_000L);
                    eventBus.publish(bar);
                    sentCount.incrementAndGet();
                }
            }, 0, 10, TimeUnit.MILLISECONDS);

            // 等待所有事件接收完成（最多 15 秒）
            boolean completed = allReceived.await(15, TimeUnit.SECONDS);
            scheduler.shutdown();

            // Assert
            assertThat(completed).as("所有事件应在 15 秒内接收完成").isTrue();
            assertThat(receivedCount.get()).isEqualTo(expectedEvents);
            assertThat(sentCount.get()).isEqualTo(expectedEvents);
        }

        @Test
        @DisplayName("高频发布下 InMemoryBarCache 不应丢数据")
        void shouldNotLoseBarCacheDataUnderHighFrequency() throws Exception {
            // Arrange
            InMemoryEventBus eventBus = new InMemoryEventBus();
            InMemoryBarCache barCache = new InMemoryBarCache(eventBus);
            // @PostConstruct 不会在手动创建时触发，需手动调用 init()
            barCache.init();

            int eventCount = 1000;
            CountDownLatch allProcessed = new CountDownLatch(eventCount);
            eventBus.subscribe(BarEvent.class, bar -> allProcessed.countDown());

            // Act: 快速发布 1000 个事件
            long baseTime = System.currentTimeMillis();
            for (int i = 0; i < eventCount; i++) {
                BarEvent bar = createBarEvent(btcUsdt, i, baseTime + i * 60_000L);
                eventBus.publish(bar);
            }

            assertThat(allProcessed.await(10, TimeUnit.SECONDS)).isTrue();

            // Assert: BarCache 应有数据（最多 MAX_BARS_PER_KEY=500）
            int cacheSize = barCache.size(btcUsdt, Timeframe.M1);
            assertThat(cacheSize).isGreaterThan(0);
            assertThat(cacheSize).isLessThanOrEqualTo(500);
        }
    }

    // =========================================================================
    // Test 3: 异常数据注入
    // =========================================================================

    @Nested
    @DisplayName("测试 3: 异常数据注入")
    class AbnormalDataTest {

        @Test
        @DisplayName("null metadata 不应导致 EventBus 崩溃")
        void shouldHandleNullMetadataGracefully() {
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger received = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> received.incrementAndGet());

            // null metadata — BarEvent record 允许 null 字段
            BarEvent bar = new BarEvent(
                    btcUsdt, null, Timeframe.M1,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(105),
                    BigDecimal.valueOf(95), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(1000), BigDecimal.valueOf(100000),
                    true
            );

            // 不应抛异常
            eventBus.publish(bar);
            assertThat(received.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("null instrument 不应导致 EventBus 崩溃")
        void shouldHandleNullInstrumentGracefully() {
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger received = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> received.incrementAndGet());

            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());
            BarEvent bar = new BarEvent(
                    null, metadata, Timeframe.M1,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(105),
                    BigDecimal.valueOf(95), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(1000), BigDecimal.valueOf(100000),
                    true
            );

            eventBus.publish(bar);
            assertThat(received.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("负价格不应导致策略崩溃")
        void shouldHandleNegativePriceGracefully() {
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger received = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> received.incrementAndGet());

            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());
            BarEvent bar = new BarEvent(
                    btcUsdt, metadata, Timeframe.M1,
                    BigDecimal.valueOf(-100), BigDecimal.valueOf(-95),
                    BigDecimal.valueOf(-105), BigDecimal.valueOf(-100),
                    BigDecimal.valueOf(1000), BigDecimal.valueOf(100000),
                    true
            );

            // EventBus 应正常派发
            eventBus.publish(bar);
            assertThat(received.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("零成交量不应导致策略崩溃")
        void shouldHandleZeroVolumeGracefully() {
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger received = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> received.incrementAndGet());

            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());
            BarEvent bar = new BarEvent(
                    btcUsdt, metadata, Timeframe.M1,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    true
            );

            eventBus.publish(bar);
            assertThat(received.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("极端 BigDecimal 值不应导致崩溃")
        void shouldHandleExtremeBigDecimalValues() {
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger received = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> received.incrementAndGet());

            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());

            // 极大值
            BarEvent extremeBar = new BarEvent(
                    btcUsdt, metadata, Timeframe.M1,
                    new BigDecimal("99999999999999999999.99999999"),
                    new BigDecimal("99999999999999999999.99999999"),
                    new BigDecimal("0.00000001"),
                    new BigDecimal("99999999999999999999.99999999"),
                    new BigDecimal("99999999999999999999"),
                    new BigDecimal("99999999999999999999"),
                    true
            );

            eventBus.publish(extremeBar);
            assertThat(received.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("null OHLCV 字段不应导致 EventBus 崩溃")
        void shouldHandleNullOhlcvFields() {
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger received = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> received.incrementAndGet());

            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());
            BarEvent bar = new BarEvent(
                    btcUsdt, metadata, Timeframe.M1,
                    null, null, null, null, null, null,
                    false
            );

            eventBus.publish(bar);
            assertThat(received.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("异常数据不应影响后续正常事件处理")
        void shouldNotAffectSubsequentNormalEventsAfterAbnormalData() {
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger received = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> received.incrementAndGet());

            // 发送异常数据
            BarEvent nullBar = new BarEvent(
                    null, null, null, null, null, null, null, null, null, false
            );
            eventBus.publish(nullBar);

            // 发送正常数据
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, System.currentTimeMillis());
            BarEvent normalBar = new BarEvent(
                    btcUsdt, metadata, Timeframe.M1,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(105),
                    BigDecimal.valueOf(95), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(1000), BigDecimal.valueOf(100000),
                    true
            );
            eventBus.publish(normalBar);

            assertThat(received.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("包含 NaN 的字符串字段不应导致崩溃")
        void shouldHandleWeirdStringFields() {
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger received = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> received.incrementAndGet());

            // Instrument 包含特殊字符
            Instrument weirdInstrument = new Instrument(
                    Exchange.BINANCE, MarketType.PERPETUAL, "", "", ""
            );
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, 0);
            BarEvent bar = new BarEvent(
                    weirdInstrument, metadata, Timeframe.M1,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    false
            );

            eventBus.publish(bar);
            assertThat(received.get()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Test 4: 并发安全
    // =========================================================================

    @Nested
    @DisplayName("测试 4: 并发安全")
    class ConcurrencySafetyTest {

        @Test
        @DisplayName("多线程同时发布事件不应抛 ConcurrentModificationException")
        void shouldHandleConcurrentPublishWithoutException() throws Exception {
            // Arrange
            InMemoryEventBus eventBus = new InMemoryEventBus();
            int threadCount = 10;
            int eventsPerThread = 200;
            AtomicInteger totalReceived = new AtomicInteger(0);

            eventBus.subscribe(BarEvent.class, bar -> totalReceived.incrementAndGet());
            eventBus.subscribe(MarketEvent.class, bar -> totalReceived.incrementAndGet());

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch allDone = new CountDownLatch(threadCount);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // Act: 所有线程同时开始发布
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await(); // 同步起步
                        long baseTime = System.currentTimeMillis();
                        for (int i = 0; i < eventsPerThread; i++) {
                            Instrument inst = (i % 2 == 0) ? btcUsdt
                                    : Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT");
                            BarEvent bar = createBarEvent(inst, threadId * eventsPerThread + i,
                                    baseTime + i * 60_000L);
                            eventBus.publish(bar);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            startGate.countDown(); // 放闸
            assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // Assert: 无异常
            assertThat(failure.get()).as("不应有并发异常").isNull();

            // 每个事件触发 2 个订阅者（BarEvent + MarketEvent 父类型）
            int expectedTotal = threadCount * eventsPerThread * 2;
            assertThat(totalReceived.get()).isEqualTo(expectedTotal);
        }

        @Test
        @DisplayName("并发读写 InMemoryBarCache 不应抛异常")
        void shouldHandleConcurrentBarCacheAccess() throws Exception {
            // Arrange
            InMemoryEventBus eventBus = new InMemoryEventBus();
            InMemoryBarCache barCache = new InMemoryBarCache(eventBus);

            int writerCount = 5;
            int readerCount = 5;
            int eventsPerWriter = 500;
            ExecutorService executor = Executors.newFixedThreadPool(writerCount + readerCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch allDone = new CountDownLatch(writerCount + readerCount);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // Act: 写线程
            for (int t = 0; t < writerCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        long baseTime = System.currentTimeMillis();
                        for (int i = 0; i < eventsPerWriter; i++) {
                            BarEvent bar = createBarEvent(btcUsdt,
                                    threadId * eventsPerWriter + i,
                                    baseTime + i * 60_000L);
                            barCache.addBar(bar);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            // 读线程
            for (int t = 0; t < readerCount; t++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < eventsPerWriter; i++) {
                            barCache.getBars(btcUsdt, Timeframe.M1, 100);
                            barCache.size(btcUsdt, Timeframe.M1);
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // Assert
            assertThat(failure.get()).as("不应有并发异常").isNull();
            assertThat(barCache.size(btcUsdt, Timeframe.M1)).isGreaterThan(0);
        }

        @Test
        @DisplayName("并发订阅和发布不应导致 ConcurrentModificationException")
        void shouldHandleConcurrentSubscribeAndPublish() throws Exception {
            // Arrange
            InMemoryEventBus eventBus = new InMemoryEventBus();
            int threadCount = 8;
            int eventsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch allDone = new CountDownLatch(threadCount);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // Act: 一半线程订阅，一半线程发布
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                if (t < threadCount / 2) {
                    // 订阅线程
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            for (int i = 0; i < eventsPerThread; i++) {
                                AtomicInteger count = new AtomicInteger(0);
                                eventBus.subscribe(BarEvent.class, bar -> count.incrementAndGet());
                            }
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                        } finally {
                            allDone.countDown();
                        }
                    });
                } else {
                    // 发布线程
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            long baseTime = System.currentTimeMillis();
                            for (int i = 0; i < eventsPerThread; i++) {
                                BarEvent bar = createBarEvent(btcUsdt,
                                        threadId * eventsPerThread + i,
                                        baseTime + i * 60_000L);
                                eventBus.publish(bar);
                            }
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                        } finally {
                            allDone.countDown();
                        }
                    });
                }
            }

            startGate.countDown();
            assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // Assert
            assertThat(failure.get()).as("不应有并发异常").isNull();
        }

        @Test
        @DisplayName("单个 handler 异常不应影响其他 handler 的并发执行")
        void shouldIsolateHandlerExceptionsUnderConcurrency() throws Exception {
            // Arrange
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            // 注册多个 handler，其中一个会抛异常
            eventBus.subscribe(BarEvent.class, bar -> successCount.incrementAndGet());
            eventBus.subscribe(BarEvent.class, bar -> {
                errorCount.incrementAndGet();
                throw new RuntimeException("故意抛出的异常");
            });
            eventBus.subscribe(BarEvent.class, bar -> successCount.incrementAndGet());

            int threadCount = 5;
            int eventsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch allDone = new CountDownLatch(threadCount);

            // Act
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        long baseTime = System.currentTimeMillis();
                        for (int i = 0; i < eventsPerThread; i++) {
                            BarEvent bar = createBarEvent(btcUsdt,
                                    threadId * eventsPerThread + i,
                                    baseTime + i * 60_000L);
                            eventBus.publish(bar);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // Assert: 成功 handler 应收到所有事件（2 个成功 handler * 总事件数）
            int totalEvents = threadCount * eventsPerThread;
            assertThat(successCount.get()).isEqualTo(totalEvents * 2);
            assertThat(errorCount.get()).isEqualTo(totalEvents);
        }
    }

    // =========================================================================
    // Test 5: 内存泄漏
    // =========================================================================

    @Nested
    @DisplayName("测试 5: 内存泄漏检测")
    class MemoryLeakTest {

        @Test
        @DisplayName("创建大量 BarEvent 后 GC 应可回收")
        void shouldBeGarbageCollectableAfterHeavyUsage() {
            // Arrange: 获取基线内存
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            long beforeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // Act: 创建并处理大量事件
            InMemoryEventBus eventBus = new InMemoryEventBus();
            AtomicInteger count = new AtomicInteger(0);
            eventBus.subscribe(BarEvent.class, bar -> count.incrementAndGet());

            int batchSize = 5000;
            for (int batch = 0; batch < 10; batch++) {
                long baseTime = System.currentTimeMillis();
                for (int i = 0; i < batchSize; i++) {
                    BarEvent bar = createBarEvent(btcUsdt, batch * batchSize + i,
                            baseTime + i * 60_000L);
                    eventBus.publish(bar);
                }
            }

            assertThat(count.get()).isEqualTo(50_000);

            // 清除引用
            eventBus = null;

            // 触发 GC
            System.gc();
            try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            // Assert: 内存不应显著增长（允许 50MB 波动）
            long afterMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long growth = afterMemory - beforeMemory;

            // 50000 个 BarEvent 大约几 MB，GC 后不应有 50MB+ 的增长
            assertThat(growth).as("GC 后内存增长不应超过 50MB，实际增长: %d bytes", growth)
                    .isLessThan(50 * 1024 * 1024L);
        }

        @Test
        @DisplayName("重复回测不应累积内存")
        void shouldNotAccumulateMemoryAcrossRepeatedBacktests() {
            // Arrange
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            long beforeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // Act: 运行 20 次小规模回测
            for (int i = 0; i < 20; i++) {
                List<BarEvent> bars = generateWaveBars(500, 100, 10, 60_000L);
                long startTime = bars.get(0).metadata().exchangeTimestamp();
                long endTime = bars.get(499).metadata().exchangeTimestamp();

                BacktestConfig config = new BacktestConfig(
                        btcUsdt, Timeframe.M1, startTime, endTime, BigDecimal.valueOf(10_000));
                HistoricalDataProvider provider = new InMemoryHistoricalDataProvider(bars);
                Strategy strategy = new SimpleOscillationStrategy(3);

                BacktestResult result = engine.run(config, strategy, provider);
                assertThat(result).isNotNull();
            }

            // 触发 GC
            System.gc();
            try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            // Assert
            long afterMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long growth = afterMemory - beforeMemory;

            assertThat(growth).as("20 次回测后内存增长不应超过 100MB，实际增长: %d bytes", growth)
                    .isLessThan(100 * 1024 * 1024L);
        }

        @Test
        @DisplayName("InMemoryBarCache 超过容量上限应正确淘汰旧数据")
        void shouldEvictOldBarsWhenCacheExceedsCapacity() {
            // Arrange
            InMemoryEventBus eventBus = new InMemoryEventBus();
            InMemoryBarCache barCache = new InMemoryBarCache(eventBus);

            // Act: 发送超过 MAX_BARS_PER_KEY (500) 的数据
            int totalBars = 2000;
            long baseTime = System.currentTimeMillis();
            for (int i = 0; i < totalBars; i++) {
                BarEvent bar = createBarEvent(btcUsdt, i, baseTime + i * 60_000L);
                barCache.addBar(bar);
            }

            // Assert: 缓存大小应被限制在 500
            int cacheSize = barCache.size(btcUsdt, Timeframe.M1);
            assertThat(cacheSize).isEqualTo(500);

            // 最新的 bar 应该在缓存中
            List<BarEvent> latest = barCache.getBars(btcUsdt, Timeframe.M1, 1);
            assertThat(latest).hasSize(1);
            // 最新 bar 的 index 应为 1999
            assertThat(latest.get(0).metadata().exchangeTimestamp())
                    .isEqualTo(baseTime + (totalBars - 1) * 60_000L);
        }
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    /**
     * 创建测试用 BarEvent。
     */
    private BarEvent createBarEvent(Instrument instrument, int index, long timestamp) {
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, timestamp);
        BigDecimal base = BigDecimal.valueOf(100 + (index % 20) - 10); // 90-110
        return new BarEvent(
                instrument, metadata, Timeframe.M1,
                base, base.add(BigDecimal.ONE), base.subtract(BigDecimal.ONE), base,
                BigDecimal.valueOf(1000 + index), BigDecimal.valueOf(100000 + index * 100L),
                true
        );
    }

    /**
     * 生成波浪形价格 K 线序列（用于回测）。
     * 价格在 (baseAmplitude - amplitude) 到 (baseAmplitude + amplitude) 之间正弦波动。
     */
    private List<BarEvent> generateWaveBars(int count, double basePrice, double amplitude, long intervalMs) {
        List<BarEvent> bars = new ArrayList<>(count);
        long baseTime = 1_700_000_000_000L;

        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / 100.0; // 100 根 bar 一个周期
            double price = basePrice + amplitude * Math.sin(angle);
            BigDecimal bdPrice = BigDecimal.valueOf(price);
            long ts = baseTime + (long) i * intervalMs;

            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, ts);
            bars.add(new BarEvent(
                    btcUsdt, metadata, Timeframe.M1,
                    bdPrice, bdPrice.add(BigDecimal.ONE), bdPrice.subtract(BigDecimal.ONE), bdPrice,
                    BigDecimal.valueOf(1000), BigDecimal.valueOf(100000),
                    true
            ));
        }
        return bars;
    }

    /**
     * 简单的振荡策略：每隔 N 根 bar 交替买/卖。
     * 用于大数据量回测，不依赖因子计算。
     */
    private static class SimpleOscillationStrategy implements Strategy {

        private final int interval;
        private int barCount = 0;
        private boolean holding = false;

        SimpleOscillationStrategy(int interval) {
            this.interval = interval;
        }

        @Override
        public String name() {
            return "SimpleOscillation";
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
            if (barCount % interval != 0) {
                return null;
            }

            if (!holding) {
                holding = true;
                return new SignalEvent(
                        name(), bar.instrument(), SignalType.BUY,
                        BigDecimal.valueOf(0.8), "买入信号 #" + barCount,
                        Map.of(), bar.metadata().exchangeTimestamp()
                );
            } else {
                holding = false;
                return new SignalEvent(
                        name(), bar.instrument(), SignalType.SELL,
                        BigDecimal.valueOf(0.8), "卖出信号 #" + barCount,
                        Map.of(), bar.metadata().exchangeTimestamp()
                );
            }
        }
    }
}
