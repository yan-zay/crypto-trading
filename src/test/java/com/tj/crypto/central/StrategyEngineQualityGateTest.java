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
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import com.tj.crypto.strategy.core.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private StrategyContext pointInTimeContext;
    private KillSwitch killSwitch;

    @BeforeEach
    void setUp() {
        strategy = mock(Strategy.class);
        when(strategy.name()).thenReturn("quality-gate-test");
        when(strategy.listenedEvents()).thenReturn(Set.of(BarEvent.class));

        StrategyManager strategyManager = mock(StrategyManager.class);
        when(strategyManager.getActiveStrategies()).thenReturn(List.of(strategy));

        StrategyContext context = mock(StrategyContext.class);
        pointInTimeContext = mock(StrategyContext.class);
        when(context.at(anyLong())).thenReturn(pointInTimeContext);

        killSwitch = new KillSwitch();
        MarketDataQualityGate qualityGate =
                new MarketDataQualityGate(new DataQualityChecker(), killSwitch);
        engine = new StrategyEngine(new InMemoryEventBus(), context, mock(SignalCollector.class),
                strategyManager, List.of(), qualityGate);
    }

    @Test
    void rejectsGapBeforeAnyStrategyCanObserveIt() {
        BarEvent first = bar(1_000L);
        BarEvent gap = bar(121_000L);

        engine.onMarketEvent(first);
        engine.onMarketEvent(gap);

        verify(strategy, times(1)).onEvent(same(first), same(pointInTimeContext));
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void dropsCompletedReplayBeforeStrategyWithoutHalting() {
        BarEvent first = bar(1_000L);

        engine.onMarketEvent(first);
        engine.onMarketEvent(first);

        verify(strategy, times(1)).onEvent(same(first), same(pointInTimeContext));
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
    }

    private BarEvent bar(long timestamp) {
        return new BarEvent(INSTRUMENT, EventMetadata.of(Exchange.BINANCE, timestamp), Timeframe.M1,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), BigDecimal.TEN, new BigDecimal("1050"), true);
    }
}
