package com.tj.crypto.central;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.quality.DataQualityChecker;
import com.tj.crypto.marketdata.quality.MarketDataQualityGate;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.strategy.core.SignalCollector;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import com.tj.crypto.strategy.core.StrategyManager;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStage;
import com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyEngineQualityGateTest {

    private static final Instrument INSTRUMENT =
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

    private Strategy strategy;
    private StrategyEngine engine;
    private InMemoryEventBus eventBus;
    private StrategyContext pointInTimeContext;
    private KillSwitch killSwitch;

    @BeforeEach
    void setUp() {
        strategy = mock(Strategy.class);
        when(strategy.name()).thenReturn("quality-gate-test");
        when(strategy.listenedEvents()).thenReturn(Set.of(BarEvent.class));

        StrategyManager strategyManager = mock(StrategyManager.class);
        when(strategyManager.allowsEvaluationEvent(any(), any(), any())).thenReturn(true);
        StrategyRuntimeRelease release = new StrategyRuntimeRelease(
                "CONFIGURATION_ONLY_TEST:quality-gate-test",
                null,
                null,
                StrategyLifecycleStage.SHADOW,
                false);
        when(strategyManager.getActiveStrategyReleases()).thenReturn(List.of(
                new StrategyManager.ActiveStrategyRelease(strategy, release)));

        StrategyContext context = mock(StrategyContext.class);
        pointInTimeContext = mock(StrategyContext.class);
        when(context.at(anyLong())).thenReturn(pointInTimeContext);

        killSwitch = new KillSwitch();
        MarketDataQualityGate qualityGate =
                new MarketDataQualityGate(new DataQualityChecker(), killSwitch);
        eventBus = new InMemoryEventBus(qualityGate);
        engine = new StrategyEngine(eventBus, context, mock(SignalCollector.class),
                strategyManager, List.of(), qualityGate);
        eventBus.subscribe(com.tj.crypto.marketdata.model.MarketEvent.class, engine::onMarketEvent);
    }

    @Test
    void rejectsGapBeforeAnyStrategyCanObserveIt() {
        BarEvent first = bar(1_000L);
        BarEvent gap = bar(121_000L);

        eventBus.publish(first);
        eventBus.publish(gap);

        verify(strategy, times(1)).onEvent(same(first), same(pointInTimeContext));
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void dropsCompletedReplayBeforeStrategyWithoutHalting() {
        BarEvent first = bar(1_000L);

        eventBus.publish(first);
        eventBus.publish(first);

        verify(strategy, times(1)).onEvent(same(first), same(pointInTimeContext));
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
    }

    @Test
    void rejectsSignalWhoseStrategyIdentityDoesNotMatchResolvedRelease() {
        SignalCollector collector = mock(SignalCollector.class);
        StrategyRuntimeRelease release = lifecycleRelease("assignment-1", "a", "b");
        StrategyManager manager = manager(List.of(active(release)));
        when(strategy.onEvent(any(), any())).thenReturn(signal("different-strategy"));
        List<String> audit = new ArrayList<>();
        StrategyEngine guarded = guardedEngine(manager, collector, audit);

        guarded.onMarketEvent(bar(1_000L));

        verify(collector, never()).collect(any());
        assertThat(audit).containsExactly("SIGNAL_STRATEGY_IDENTITY_MISMATCH");
    }

    @Test
    void rejectsSignalForAnInstrumentOtherThanItsTriggeringEvent() {
        SignalCollector collector = mock(SignalCollector.class);
        StrategyRuntimeRelease release = lifecycleRelease("assignment-1", "a", "b");
        StrategyManager manager = manager(List.of(active(release)));
        Instrument other = Instrument.of(Exchange.OKX, MarketType.PERPETUAL, "ETHUSDT");
        String strategyName = strategy.name();
        when(strategy.onEvent(any(), any())).thenReturn(new SignalEvent(strategyName, other,
                SignalType.BUY, BigDecimal.ONE, "fixture", Map.of(), 1_000L));
        List<String> audit = new ArrayList<>();

        guardedEngine(manager, collector, audit).onMarketEvent(bar(1_000L));

        verify(collector, never()).collect(any());
        assertThat(audit).containsExactly("SIGNAL_CAUSAL_INSTRUMENT_MISMATCH");
    }

    @Test
    void rejectsSignalWhoseTimestampIsNotTheTriggeringFactTime() {
        SignalCollector collector = mock(SignalCollector.class);
        StrategyRuntimeRelease release = lifecycleRelease("assignment-1", "a", "b");
        StrategyManager manager = manager(List.of(active(release)));
        String strategyName = strategy.name();
        when(strategy.onEvent(any(), any())).thenReturn(new SignalEvent(strategyName, INSTRUMENT,
                SignalType.BUY, BigDecimal.ONE, "fixture", Map.of(), 999L));
        List<String> audit = new ArrayList<>();

        guardedEngine(manager, collector, audit).onMarketEvent(bar(1_000L));

        verify(collector, never()).collect(any());
        assertThat(audit).containsExactly("SIGNAL_CAUSAL_TIMESTAMP_MISMATCH");
    }

    @Test
    void rejectsSignalWhenReleaseChangesWhileStrategyIsExecuting() {
        SignalCollector collector = mock(SignalCollector.class);
        StrategyRuntimeRelease before = lifecycleRelease("assignment-1", "a", "b");
        StrategyRuntimeRelease after = lifecycleRelease("assignment-2", "c", "d");
        StrategyManager manager = mock(StrategyManager.class);
        when(manager.allowsEvaluationEvent(any(), any(), any())).thenReturn(true);
        when(manager.getActiveStrategyReleases()).thenReturn(
                List.of(active(before)), List.of(active(after)));
        String strategyName = strategy.name();
        when(strategy.onEvent(any(), any())).thenReturn(signal(strategyName));
        List<String> audit = new ArrayList<>();

        guardedEngine(manager, collector, audit).onMarketEvent(bar(1_000L));

        verify(collector, never()).collect(any());
        assertThat(audit).containsExactly("RUNTIME_RELEASE_CHANGED_DURING_EXECUTION");
    }

    @Test
    void rejectsSignalWhenReleaseExpiresDuringStrategyExecution() {
        SignalCollector collector = mock(SignalCollector.class);
        StrategyRuntimeRelease before = lifecycleRelease("assignment-1", "a", "b");
        StrategyManager manager = mock(StrategyManager.class);
        when(manager.allowsEvaluationEvent(any(), any(), any())).thenReturn(true);
        when(manager.getActiveStrategyReleases()).thenReturn(List.of(active(before)), List.of());
        String strategyName = strategy.name();
        when(strategy.onEvent(any(), any())).thenReturn(signal(strategyName));
        List<String> audit = new ArrayList<>();

        guardedEngine(manager, collector, audit).onMarketEvent(bar(1_000L));

        verify(collector, never()).collect(any());
        assertThat(audit).containsExactly("RUNTIME_RELEASE_NO_LONGER_CURRENT");
    }

    private StrategyEngine guardedEngine(StrategyManager manager, SignalCollector collector,
                                         List<String> audit) {
        StrategyContext context = mock(StrategyContext.class);
        when(context.at(anyLong())).thenReturn(pointInTimeContext);
        return new StrategyEngine(new InMemoryEventBus(), context, collector, manager, List.of(),
                new MarketDataQualityGate(new DataQualityChecker(), new KillSwitch()),
                (reason, expected, actual, before, after) -> audit.add(reason));
    }

    private StrategyManager manager(List<StrategyManager.ActiveStrategyRelease> releases) {
        StrategyManager manager = mock(StrategyManager.class);
        when(manager.allowsEvaluationEvent(any(), any(), any())).thenReturn(true);
        when(manager.getActiveStrategyReleases()).thenReturn(releases);
        return manager;
    }

    private StrategyManager.ActiveStrategyRelease active(StrategyRuntimeRelease release) {
        return new StrategyManager.ActiveStrategyRelease(strategy, release);
    }

    private StrategyRuntimeRelease lifecycleRelease(String assignment, String artifactSeed,
                                                     String subjectSeed) {
        return new StrategyRuntimeRelease(assignment, artifactSeed.repeat(64), subjectSeed.repeat(64),
                StrategyLifecycleStage.SHADOW, true);
    }

    private SignalEvent signal(String strategyName) {
        return new SignalEvent(strategyName, INSTRUMENT, SignalType.BUY, BigDecimal.ONE,
                "fixture", Map.of(), 1_000L);
    }

    private BarEvent bar(long timestamp) {
        return new BarEvent(INSTRUMENT, EventMetadata.of(Exchange.BINANCE, timestamp), Timeframe.M1,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), BigDecimal.TEN, new BigDecimal("1050"), true);
    }
}
