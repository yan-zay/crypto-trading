package com.tj.crypto.central;

import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.quality.DataQualityChecker;
import com.tj.crypto.marketdata.quality.MarketDataQualityGate;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalListener;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import com.tj.crypto.strategy.core.StrategyManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;

import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/**
 * 策略引擎。
 * 接收市场事件，按策略的监听条件分发到对应策略执行。
 *
 * 分发逻辑：
 * 1. BarEvent 必须先通过运行时行情质量门
 * 2. 从 StrategyManager 获取活跃（已启用）策略
 * 3. 遍历活跃策略，检查 listenedEvents() 是否包含事件类型
 * 4. 匹配策略按 strategy + instrument + timeframe 分区串行执行
 * 5. 策略异常不传播，由 try-catch 隔离
 */
@Slf4j
@Component
@DependsOn("inMemoryBarCache")
public class StrategyEngine {

    private final MarketEventBus eventBus;
    private final StrategyContext strategyContext;
    private final SignalCollector signalCollector;
    private final StrategyManager strategyManager;
    private final List<SignalListener> signalListeners;
    private final MarketDataQualityGate marketDataQualityGate;
    private final SignalLineageAudit signalLineageAudit;
    private final ConcurrentHashMap<String, Object> partitionLocks = new ConcurrentHashMap<>();

    @Autowired
    public StrategyEngine(MarketEventBus eventBus,
                          StrategyContext strategyContext,
                          SignalCollector signalCollector,
                          StrategyManager strategyManager,
                          List<SignalListener> signalListeners,
                          MarketDataQualityGate marketDataQualityGate) {
        this(eventBus, strategyContext, signalCollector, strategyManager, signalListeners,
                marketDataQualityGate, StrategyEngine::logSignalLineageRejection);
    }

    StrategyEngine(MarketEventBus eventBus,
                   StrategyContext strategyContext,
                   SignalCollector signalCollector,
                   StrategyManager strategyManager,
                   List<SignalListener> signalListeners,
                   MarketDataQualityGate marketDataQualityGate,
                   SignalLineageAudit signalLineageAudit) {
        this.eventBus = eventBus;
        this.strategyContext = strategyContext;
        this.signalCollector = signalCollector;
        this.strategyManager = strategyManager;
        this.signalListeners = List.copyOf(signalListeners);
        this.marketDataQualityGate = marketDataQualityGate;
        this.signalLineageAudit = signalLineageAudit;
    }

    /** Preserve the pre-quality-gate assembly API used by isolated tests and embedders. */
    public StrategyEngine(MarketEventBus eventBus,
                          StrategyContext strategyContext,
                          SignalCollector signalCollector,
                          StrategyManager strategyManager,
                          List<SignalListener> signalListeners) {
        this(eventBus, strategyContext, signalCollector, strategyManager, signalListeners,
                new MarketDataQualityGate(new DataQualityChecker(), new KillSwitch()));
    }

    public StrategyEngine(MarketEventBus eventBus,
                          StrategyContext strategyContext,
                          SignalCollector signalCollector,
                          StrategyManager strategyManager) {
        this(eventBus, strategyContext, signalCollector, strategyManager, List.of());
    }

    /** 兼容旧的测试/组装代码；策略执行已改为确定性的分区串行模式。 */
    public StrategyEngine(ThreadPoolTaskExecutor ignoredExecutor,
                          MarketEventBus eventBus,
                          StrategyContext strategyContext,
                          SignalCollector signalCollector,
                          StrategyManager strategyManager) {
        this(eventBus, strategyContext, signalCollector, strategyManager);
    }

    @PostConstruct
    public void init() {
        eventBus.subscribe(MarketEvent.class, this::onMarketEvent);
        log.info("StrategyEngine initialized: {} active strategies",
                strategyManager.getActiveStrategies().size());
    }

    /**
     * 标准化市场事件入口。
     * 由 MarketEventBus 调用，分发到匹配的活跃策略。
     * 禁用的策略不会收到事件。
     */
    public void onMarketEvent(MarketEvent event) {
        if (event instanceof BarEvent bar
                && !marketDataQualityGate.evaluate(bar).dispatchToStrategies()) {
            return;
        }

        for (StrategyManager.ActiveStrategyRelease active : strategyManager.getActiveStrategyReleases()) {
            Strategy strategy = active.strategy();
            if (strategy.listenedEvents().contains(event.getClass())) {
                String seriesSuffix = event instanceof BarEvent bar
                        ? ":" + bar.timeframe().getCode() : ":" + event.getClass().getSimpleName();
                String partition = strategy.name() + ":" + event.instrument().id().value() + seriesSuffix;
                Object lock = partitionLocks.computeIfAbsent(partition, ignored -> new Object());
                synchronized (lock) {
                    try {
                        StrategyContext pointInTimeContext = strategyContext.at(
                                event.metadata().exchangeTimestamp());
                        SignalEvent signal = strategy.onEvent(event, pointInTimeContext);
                        if (signal != null) {
                            if (!strategy.name().equals(signal.strategyName())) {
                                rejectSignal("SIGNAL_STRATEGY_IDENTITY_MISMATCH", strategy, signal,
                                        active.release(), null);
                                continue;
                            }
                            if (signal.instrument() == null
                                    || !event.instrument().id().equals(signal.instrument().id())) {
                                rejectSignal("SIGNAL_CAUSAL_INSTRUMENT_MISMATCH", strategy, signal,
                                        active.release(), null);
                                continue;
                            }
                            if (signal.timestamp() != event.metadata().exchangeTimestamp()) {
                                rejectSignal("SIGNAL_CAUSAL_TIMESTAMP_MISMATCH", strategy, signal,
                                        active.release(), null);
                                continue;
                            }
                            StrategyManager.ActiveStrategyRelease refreshed = strategyManager
                                    .getActiveStrategyReleases().stream()
                                    .filter(candidate -> strategy.name().equals(candidate.strategy().name()))
                                    .findFirst().orElse(null);
                            if (refreshed == null) {
                                rejectSignal("RUNTIME_RELEASE_NO_LONGER_CURRENT", strategy, signal,
                                        active.release(), null);
                                continue;
                            }
                            if (!active.release().equals(refreshed.release())) {
                                rejectSignal("RUNTIME_RELEASE_CHANGED_DURING_EXECUTION", strategy, signal,
                                        active.release(), refreshed.release());
                                continue;
                            }
                            if (refreshed.release().lifecycleBound()) {
                                signal = signal.withRuntimeRelease(strategy.name(), refreshed.release());
                            }
                            signalCollector.collect(signal);
                            for (SignalListener listener : signalListeners) {
                                listener.onSignal(signal);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Strategy {} error: {}", strategy.name(), e.getMessage(), e);
                    }
                }
            }
        }
    }

    private void rejectSignal(String reasonCode, Strategy strategy, SignalEvent signal,
                              com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease before,
                              com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease after) {
        signalLineageAudit.rejected(reasonCode, strategy.name(), signal.strategyName(), before, after);
    }

    private static void logSignalLineageRejection(
            String reasonCode, String expectedStrategyName, String actualStrategyName,
            com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease before,
            com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease after) {
        log.warn("Strategy signal rejected: reasonCode={}, expectedStrategy={}, actualStrategy={}, "
                        + "releaseBefore={}, releaseAfter={}",
                reasonCode, expectedStrategyName, actualStrategyName, before, after);
    }

    @FunctionalInterface
    interface SignalLineageAudit {
        void rejected(String reasonCode, String expectedStrategyName, String actualStrategyName,
                      com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease before,
                      com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease after);
    }
}
