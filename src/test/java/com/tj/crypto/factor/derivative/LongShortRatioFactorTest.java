package com.tj.crypto.factor.derivative;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.cache.InMemoryBarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorQuality;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LongShortRatioFactorTest {

    private BarCache barCache;
    private LongShortRatioFactor ratioFactor;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        barCache = new InMemoryBarCache(new InMemoryEventBus());
        ratioFactor = new LongShortRatioFactor(barCache);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private void addBar(double open, double close, double volume, long timestampMinutes) {
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, timestampMinutes * 60000L);
        barCache.addBar(new BarEvent(btcUsdt, metadata, Timeframe.M1,
                BigDecimal.valueOf(open), BigDecimal.valueOf(Math.max(open, close) + 1),
                BigDecimal.valueOf(Math.min(open, close) - 1), BigDecimal.valueOf(close),
                BigDecimal.valueOf(volume), BigDecimal.valueOf(close * volume), true));
    }

    @Test
    @DisplayName("数据不足时应返回 WARMUP")
    void shouldReturnWarmupWhenInsufficientData() {
        addBar(100, 101, 100, 1);
        addBar(101, 102, 100, 2);

        Factor result = ratioFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.WARMUP);
    }

    @Test
    @DisplayName("数据充足时应返回 READY 和正值")
    void shouldReturnReadyAndPositiveValue() {
        // 添加上涨和下跌交替的 bar
        addBar(100, 101, 100, 1);
        addBar(101, 100, 80, 2);
        addBar(100, 102, 120, 3);
        addBar(102, 101, 90, 4);
        addBar(101, 103, 110, 5);
        addBar(103, 102, 70, 6);

        Factor result = ratioFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        assertThat(result.value()).isPositive();
    }

    @Test
    @DisplayName("因子名称应为 LONG_SHORT_RATIO")
    void shouldReturnCorrectName() {
        assertThat(ratioFactor.name()).isEqualTo("LONG_SHORT_RATIO");
    }
}
