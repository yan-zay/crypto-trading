package com.tj.crypto.admin.application;

import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.config.properties.MarketUniverseProperties;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.storage.service.AutoBackfillService;
import com.tj.crypto.storage.service.BarEventPersistenceService;
import com.tj.crypto.strategy.core.StrategyManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BacktestApplicationServiceTest {

    @Test
    void registeredStrategyJobHonorsAutoBackfill() {
        AutoBackfillService backfill = mock(AutoBackfillService.class);
        BarEventPersistenceService bars = mock(BarEventPersistenceService.class);
        when(bars.loadByTimeRange(any(), any(), anyLong(), anyLong())).thenReturn(List.of());
        BacktestApplicationService service = service(bars, backfill);

        assertThatThrownBy(() -> service.run("MacdCross", Exchange.BINANCE,
                MarketType.PERPETUAL, "BTCUSDT", "1m", 1, 50,
                BigDecimal.valueOf(10_000), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after backfill");

        verify(backfill).backfillIfNeeded(Exchange.BINANCE, MarketType.PERPETUAL,
                "BTCUSDT", "1m", 2);
    }

    @Test
    void synchronousCompatibilityPathDoesNotBackfillImplicitly() {
        AutoBackfillService backfill = mock(AutoBackfillService.class);
        BarEventPersistenceService bars = mock(BarEventPersistenceService.class);
        when(bars.loadByTimeRange(any(), any(), anyLong(), anyLong())).thenReturn(List.of());
        BacktestApplicationService service = service(bars, backfill);

        assertThatThrownBy(() -> service.run("MacdCross", Exchange.BINANCE,
                MarketType.PERPETUAL, "BTCUSDT", "1m", 1, 50,
                BigDecimal.valueOf(10_000)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(backfill, never()).backfillIfNeeded(any(), any(), any(), any(), anyInt());
    }

    private BacktestApplicationService service(BarEventPersistenceService bars,
                                               AutoBackfillService backfill) {
        MarketUniverseProperties universe = mock(MarketUniverseProperties.class);
        return new BacktestApplicationService(mock(BacktestEngine.class), bars,
                mock(StrategyManager.class), mock(FactorRegistry.class), backfill, universe);
    }
}
