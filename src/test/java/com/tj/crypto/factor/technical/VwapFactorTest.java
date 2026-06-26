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

class VwapFactorTest {

    private BarCache barCache;
    private VwapFactor vwapFactor;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        barCache = new InMemoryBarCache(new InMemoryEventBus());
        vwapFactor = new VwapFactor(barCache);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    /**
     * 添加 bar 数据，时间戳设置在同一天内。
     */
    private void addBarsSameDay(int count, double startPrice, double volume) {
        long dayStart = System.currentTimeMillis() - (System.currentTimeMillis() % 86_400_000L);
        for (int i = 0; i < count; i++) {
            EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, dayStart + i * 60000L);
            double price = startPrice + i;
            barCache.addBar(new BarEvent(btcUsdt, metadata, Timeframe.M1,
                    BigDecimal.valueOf(price), BigDecimal.valueOf(price + 2),
                    BigDecimal.valueOf(price - 2), BigDecimal.valueOf(price),
                    BigDecimal.valueOf(volume), BigDecimal.valueOf(price * volume), true));
        }
    }

    @Test
    @DisplayName("数据为空时应返回 WARMUP")
    void shouldReturnWarmupWhenNoData() {
        Factor result = vwapFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.WARMUP);
    }

    @Test
    @DisplayName("数据充足时应返回 READY")
    void shouldReturnReadyWhenSufficientData() {
        addBarsSameDay(10, 100, 100);

        Factor result = vwapFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
    }

    @Test
    @DisplayName("因子名称应为 VWAP")
    void shouldReturnCorrectName() {
        assertThat(vwapFactor.name()).isEqualTo("VWAP");
    }

    @Test
    @DisplayName("VWAP 应在价格范围内")
    void shouldReturnReasonableValue() {
        // 价格从 100 到 109，VWAP 应在 100-109 之间
        addBarsSameDay(10, 100, 100);

        Factor result = vwapFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        assertThat(result.value().doubleValue()).isBetween(100.0, 110.0);
    }
}
