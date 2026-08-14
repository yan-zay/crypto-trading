package com.tj.crypto.factor.technical;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.factor.FactorProperties;
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

class EmaFactorTest {

    private BarCache barCache;
    private EmaFactor emaFactor;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        barCache = new InMemoryBarCache(new InMemoryEventBus());
        emaFactor = new EmaFactor(barCache, new FactorProperties());
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
        addBars(5, 100); // 只有 5 根，EMA_20 需要 20 根

        Factor result = emaFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.WARMUP);
    }

    @Test
    @DisplayName("数据充足时应返回 READY 和正确值")
    void shouldCalculateEmaCorrectly() {
        // 添加 25 根 bar，价格从 100 递增到 124
        addBars(25, 100);

        Factor result = emaFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        assertThat(result.name()).isEqualTo("EMA");
        // EMA 对递增序列应产生正值，且介于数据范围内
        assertThat(result.value()).isBetween(BigDecimal.valueOf(100), BigDecimal.valueOf(125));
    }

    @Test
    @DisplayName("因子名称应为 EMA_20")
    void shouldReturnCorrectName() {
        assertThat(emaFactor.name()).isEqualTo("EMA");
    }
}
