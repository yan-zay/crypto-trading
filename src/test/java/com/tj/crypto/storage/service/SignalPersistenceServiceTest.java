package com.tj.crypto.storage.service;

import com.tj.crypto.backtest.paper.PaperTradingEngine;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.storage.mapper.SignalEventMapper;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStage;
import com.tj.crypto.strategy.lifecycle.StrategyRuntimeRelease;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SignalPersistenceServiceTest {

    @Test
    void durablyInsertsSignalBeforeInvokingPaperConsumer() {
        SignalEventMapper mapper = mock(SignalEventMapper.class);
        PaperTradingEngine paper = mock(PaperTradingEngine.class);
        doAnswer(invocation -> {
            com.tj.crypto.storage.entity.SignalEventDO row = invocation.getArgument(0);
            row.setId(42L);
            return 1;
        }).when(mapper).insert(any(com.tj.crypto.storage.entity.SignalEventDO.class));
        SignalPersistenceService service = new SignalPersistenceService(mapper, paper);
        SignalEvent signal = signal();

        service.onSignal(signal);

        InOrder order = inOrder(mapper, paper);
        order.verify(mapper).insert(any(com.tj.crypto.storage.entity.SignalEventDO.class));
        order.verify(paper).executeSignal(signal);
    }

    @Test
    void paperFailurePropagatesSoSpringCanRollBackSignalAndOrderFactsTogether() {
        SignalEventMapper mapper = mock(SignalEventMapper.class);
        PaperTradingEngine paper = mock(PaperTradingEngine.class);
        doAnswer(invocation -> {
            com.tj.crypto.storage.entity.SignalEventDO row = invocation.getArgument(0);
            row.setId(42L);
            return 1;
        }).when(mapper).insert(any(com.tj.crypto.storage.entity.SignalEventDO.class));
        doThrow(new IllegalStateException("paper persistence unavailable"))
                .when(paper).executeSignal(any());
        SignalPersistenceService service = new SignalPersistenceService(mapper, paper);

        assertThatThrownBy(() -> service.onSignal(signal()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paper persistence unavailable");
    }

    @Test
    void shadowReleaseIsDurablyRoutedWithoutInvokingPaperExecution() {
        SignalEventMapper mapper = mapperThatAssignsId();
        PaperTradingEngine paper = mock(PaperTradingEngine.class);
        SignalPersistenceService service = new SignalPersistenceService(mapper, paper);
        SignalEvent shadow = signal().withRuntimeRelease(new StrategyRuntimeRelease(
                "assignment-shadow", "a".repeat(64), "b".repeat(64),
                StrategyLifecycleStage.SHADOW, true));

        service.onSignal(shadow);

        verify(paper, never()).executeSignal(any());
        org.mockito.ArgumentCaptor<com.tj.crypto.storage.entity.SignalEventDO> row =
                org.mockito.ArgumentCaptor.forClass(com.tj.crypto.storage.entity.SignalEventDO.class);
        verify(mapper).insert(row.capture());
        org.assertj.core.api.Assertions.assertThat(row.getValue().getDeliveryTarget())
                .isEqualTo("SHADOW");
        org.assertj.core.api.Assertions.assertThat(row.getValue().getLifecycleStage())
                .isEqualTo("SHADOW");
    }

    @Test
    void canaryReleaseRoutesOnlyToPaperAndNeverToLiveGateway() {
        SignalEventMapper mapper = mapperThatAssignsId();
        PaperTradingEngine paper = mock(PaperTradingEngine.class);
        SignalPersistenceService service = new SignalPersistenceService(mapper, paper);
        SignalEvent canary = signal().withRuntimeRelease(new StrategyRuntimeRelease(
                "assignment-canary", "c".repeat(64), "d".repeat(64),
                StrategyLifecycleStage.CANARY, true));

        service.onSignal(canary);

        verify(paper).executeSignal(canary);
    }

    private SignalEventMapper mapperThatAssignsId() {
        SignalEventMapper mapper = mock(SignalEventMapper.class);
        doAnswer(invocation -> {
            com.tj.crypto.storage.entity.SignalEventDO row = invocation.getArgument(0);
            row.setId(42L);
            return 1;
        }).when(mapper).insert(any(com.tj.crypto.storage.entity.SignalEventDO.class));
        return mapper;
    }

    private SignalEvent signal() {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        return new SignalEvent("MacdCross", instrument, SignalType.BUY, BigDecimal.ONE,
                "fixture", Map.of("MACD", BigDecimal.ONE), 1_000);
    }
}
