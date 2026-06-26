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

class SmaFactorTest {

    private BarCache barCache;
    private SmaFactor smaFactor;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        barCache = new InMemoryBarCache(new InMemoryEventBus());
        smaFactor = new SmaFactor(barCache);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private void addBars(int count, double startPrice) {
        for (int i = 0; i < count; i++) {
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, i * 60000L);
            double price = startPrice + i; // 递增价格
            barCache.addBar(new BarEvent(btcUsdt, metadata, Timeframe.M1,
                    BigDecimal.valueOf(price - 1), BigDecimal.valueOf(price + 1),
                    BigDecimal.valueOf(price - 2), BigDecimal.valueOf(price),
                    BigDecimal.valueOf(100), BigDecimal.valueOf(price * 100), true));
        }
    }

    @Test
    @DisplayName("数据不足时应返回 WARMUP")
    void shouldReturnWarmupWhenInsufficientData() {
        addBars(5, 100); // 只有 5 根，SMA_20 需要 20 根

        Factor result = smaFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.WARMUP);
    }

    @Test
    @DisplayName("数据充足时应返回 READY 和正确值")
    void shouldCalculateSmaCorrectly() {
        // 添加 25 根 bar，价格从 100 递增到 124
        addBars(25, 100);

        Factor result = smaFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        assertThat(result.name()).isEqualTo("SMA_20");
        // SMA_20 的最后 20 根价格是 105-124，平均值 = (105+124)/2 = 114.5
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.valueOf(114.5));
    }

    @Test
    @DisplayName("因子名称应为 SMA_20")
    void shouldReturnCorrectName() {
        assertThat(smaFactor.name()).isEqualTo("SMA_20");
    }
}
