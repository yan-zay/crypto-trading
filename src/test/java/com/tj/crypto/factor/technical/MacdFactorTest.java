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

class MacdFactorTest {

    private BarCache barCache;
    private MacdFactor macdFactor;
    private Instrument btcUsdt;

    // MACD 需要 slowPeriod(26) + signalPeriod(9) + 10 = 45 根 bar
    private static final int REQUIRED_BARS = 45;

    @BeforeEach
    void setUp() {
        barCache = new InMemoryBarCache(new InMemoryEventBus());
        macdFactor = new MacdFactor(barCache, new FactorProperties());
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
        addBars(20, 100); // 只有 20 根，MACD 需要 45 根

        Factor result = macdFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.WARMUP);
    }

    @Test
    @DisplayName("数据充足时应返回 READY")
    void shouldReturnReadyWhenSufficientData() {
        addBars(REQUIRED_BARS + 5, 100);

        Factor result = macdFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
    }

    @Test
    @DisplayName("因子名称应为 MACD_HIST")
    void shouldReturnCorrectName() {
        assertThat(macdFactor.name()).isEqualTo("MACD_HIST");
    }

    @Test
    @DisplayName("持续上涨时 histogram 应为正值")
    void shouldReturnPositiveHistogramWhenPricesRise() {
        // 持续递增价格：快速 EMA 会追上慢速 EMA，histogram 应为正
        addBars(REQUIRED_BARS + 10, 100);

        Factor result = macdFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        assertThat(result.value()).isPositive();
    }
}
