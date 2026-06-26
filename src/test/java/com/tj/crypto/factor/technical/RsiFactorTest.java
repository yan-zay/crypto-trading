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

class RsiFactorTest {

    private BarCache barCache;
    private RsiFactor rsiFactor;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        barCache = new InMemoryBarCache(new InMemoryEventBus());
        rsiFactor = new RsiFactor(barCache);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private void addBars(int count, double startPrice, double increment) {
        for (int i = 0; i < count; i++) {
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, i * 60000L);
            double price = startPrice + i * increment;
            barCache.addBar(new BarEvent(btcUsdt, metadata, Timeframe.M1,
                    BigDecimal.valueOf(price - 1), BigDecimal.valueOf(price + 1),
                    BigDecimal.valueOf(price - 2), BigDecimal.valueOf(price),
                    BigDecimal.valueOf(100), BigDecimal.valueOf(price * 100), true));
        }
    }

    @Test
    @DisplayName("数据不足时应返回 WARMUP")
    void shouldReturnWarmupWhenInsufficientData() {
        addBars(5, 100, 1);

        Factor result = rsiFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.WARMUP);
    }

    @Test
    @DisplayName("持续上涨时 RSI 应接近 100")
    void shouldReturnHighRsiForUptrend() {
        // 30 根 bar，每根涨 10
        addBars(30, 100, 10);

        Factor result = rsiFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        // 持续上涨，RSI 应该很高（接近 100）
        assertThat(result.value().doubleValue()).isGreaterThan(90);
    }

    @Test
    @DisplayName("因子名称应为 RSI_14")
    void shouldReturnCorrectName() {
        assertThat(rsiFactor.name()).isEqualTo("RSI_14");
    }
}
