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

class VolumeChangeFactorTest {

    private BarCache barCache;
    private VolumeChangeFactor volumeChangeFactor;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        barCache = new InMemoryBarCache(new InMemoryEventBus());
        volumeChangeFactor = new VolumeChangeFactor(barCache);
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private void addBar(double price, double volume, long timestampMinutes) {
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, timestampMinutes * 60000L);
        barCache.addBar(new BarEvent(btcUsdt, metadata, Timeframe.M1,
                BigDecimal.valueOf(price), BigDecimal.valueOf(price + 1),
                BigDecimal.valueOf(price - 1), BigDecimal.valueOf(price),
                BigDecimal.valueOf(volume), BigDecimal.valueOf(price * volume), true));
    }

    @Test
    @DisplayName("数据不足时应返回 WARMUP")
    void shouldReturnWarmupWhenInsufficientData() {
        addBar(100, 100, 1);

        Factor result = volumeChangeFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.WARMUP);
    }

    @Test
    @DisplayName("数据充足时应返回 READY")
    void shouldReturnReadyWhenSufficientData() {
        addBar(100, 100, 1);
        addBar(101, 150, 2);

        Factor result = volumeChangeFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
    }

    @Test
    @DisplayName("因子名称应为 VOLUME_CHANGE_PCT")
    void shouldReturnCorrectName() {
        assertThat(volumeChangeFactor.name()).isEqualTo("VOLUME_CHANGE_PCT");
    }

    @Test
    @DisplayName("成交量增加 50% 时应返回 50")
    void shouldCalculateCorrectChangePercentage() {
        addBar(100, 100, 1);
        addBar(101, 150, 2);

        Factor result = volumeChangeFactor.calculate(btcUsdt, Timeframe.M1);

        assertThat(result.quality()).isEqualTo(FactorQuality.READY);
        // (150 - 100) / 100 * 100 = 50%
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }
}
