package com.tj.crypto.strategy.impl;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorQuality;
import com.tj.crypto.factor.technical.MacdFactor;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.strategy.core.StrategyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MacdCrossStrategyTest {

    private MacdCrossStrategy strategy;
    private StrategyContext context;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        strategy = new MacdCrossStrategy();
        context = mock(StrategyContext.class);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    @Test
    @DisplayName("策略名称应为 MacdCross")
    void shouldReturnCorrectName() {
        assertThat(strategy.name()).isEqualTo("MacdCross");
    }

    @Test
    @DisplayName("应监听 BarEvent")
    void shouldListenToBarEvent() {
        assertThat(strategy.listenedEvents()).contains(BarEvent.class);
    }

    @Test
    @DisplayName("因子未就绪时不应抛异常")
    void shouldHandleFactorNotReady() {
        when(context.getFactor(eq("MACD_HIST"), any(), any())).thenReturn(null);

        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, 1000L);
        BarEvent bar = new BarEvent(btcUsdt, metadata, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), true);

        strategy.onEvent(bar, context);
        // 不抛异常即通过
    }

    @Test
    @DisplayName("非 closed bar 应被忽略")
    void shouldIgnoreNonClosedBar() {
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, 1000L);
        BarEvent bar = new BarEvent(btcUsdt, metadata, Timeframe.M1,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(5),
                BigDecimal.valueOf(100), BigDecimal.valueOf(500), false);

        strategy.onEvent(bar, context);
        // 不抛异常即通过
    }
}
