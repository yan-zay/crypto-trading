package com.tj.crypto.factor.technical;

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

class SuperTrendFactorTest {

    private BarCache barCache;
    private SuperTrendFactor superTrendFactor;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        barCache = new InMemoryBarCache(new InMemoryEventBus());
        superTrendFactor = new SuperTrendFactor(barCache);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private void addBars(int count, double startPrice, double increment, double volatility) {
        for (int i = 0; i < count; i++) {
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, i * 60000L);
            double price = startPrice + i * increment;
            barCache.addBar(new BarEvent(btcUsdt, metadata, Timeframe.M1,
                    BigDecimal.valueOf(price), BigDecimal.valueOf(price + volatility),
                    BigDecimal.valueOf(price - volatility), BigDecimal.valueOf(price + increment),
                    BigDecimal.valueOf(100), BigDecimal.valueOf(price * 100), true));
        }
    }

    @Test
    @DisplayName("数据不足时应返回 WARMUP")
    void shouldReturnWarmupWhenInsufficientData() {
        addBars(5, 100, 1, 2);

        Factor result = superTrendFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.WARMUP);
    }

    @Test
    @DisplayName("数据充足时应返回 READY")
    void shouldReturnReadyWhenSufficientData() {
        addBars(30, 100, 1, 2);

        Factor result = superTrendFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
    }

    @Test
    @DisplayName("因子名称应为 SUPERTREND")
    void shouldReturnCorrectName() {
        assertThat(superTrendFactor.name()).isEqualTo("SUPERTREND");
    }

    @Test
    @DisplayName("持续上涨时应返回上升趋势（1）")
    void shouldReturnUptrendForRisingPrices() {
        // 持续上涨，波动性小
        addBars(30, 100, 5, 1);

        Factor result = superTrendFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        assertThat(result.value().intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("持续下跌时应返回下降趋势（-1）")
    void shouldReturnDowntrendForFallingPrices() {
        // 持续下跌
        addBars(30, 200, -5, 1);

        Factor result = superTrendFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        assertThat(result.value().intValue()).isEqualTo(-1);
    }
}
