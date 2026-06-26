package com.tj.crypto.factor.cache;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBarCacheTest {

    private InMemoryBarCache cache;
    private Instrument btcUsdt;
    private Instrument ethUsdt;

    @BeforeEach
    void setUp() {
        cache = new InMemoryBarCache(new InMemoryEventBus());
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        ethUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT");
    }

    private BarEvent createBar(Instrument instrument, Timeframe timeframe, long timestamp, double close) {
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, timestamp);
        return new BarEvent(instrument, metadata, timeframe,
                BigDecimal.valueOf(close - 1), BigDecimal.valueOf(close + 1),
                BigDecimal.valueOf(close - 2), BigDecimal.valueOf(close),
                BigDecimal.valueOf(100), BigDecimal.valueOf(close * 100), true);
    }

    @Test
    @DisplayName("添加 bar 后应可查询到")
    void shouldReturnAddedBars() {
        BarEvent bar = createBar(btcUsdt, Timeframe.M1, 1000L, 16000);
        cache.addBar(bar);

        List<BarEvent> result = cache.getBars(btcUsdt, Timeframe.M1, 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).close()).isEqualByComparingTo(BigDecimal.valueOf(16000));
    }

    @Test
    @DisplayName("不同 Instrument 应独立缓存")
    void shouldCacheSeparatelyByInstrument() {
        cache.addBar(createBar(btcUsdt, Timeframe.M1, 1000L, 16000));
        cache.addBar(createBar(ethUsdt, Timeframe.M1, 1000L, 1200));

        assertThat(cache.getBars(btcUsdt, Timeframe.M1, 10)).hasSize(1);
        assertThat(cache.getBars(ethUsdt, Timeframe.M1, 10)).hasSize(1);
    }

    @Test
    @DisplayName("不同 Timeframe 应独立缓存")
    void shouldCacheSeparatelyByTimeframe() {
        cache.addBar(createBar(btcUsdt, Timeframe.M1, 1000L, 16000));
        cache.addBar(createBar(btcUsdt, Timeframe.M5, 1000L, 16000));

        assertThat(cache.getBars(btcUsdt, Timeframe.M1, 10)).hasSize(1);
        assertThat(cache.getBars(btcUsdt, Timeframe.M5, 10)).hasSize(1);
    }

    @Test
    @DisplayName("getBars 应返回最近 N 根 bar")
    void shouldReturnLastNBars() {
        for (int i = 0; i < 10; i++) {
            cache.addBar(createBar(btcUsdt, Timeframe.M1, i * 60000L, 16000 + i));
        }

        List<BarEvent> result = cache.getBars(btcUsdt, Timeframe.M1, 3);
        assertThat(result).hasSize(3);
        // 最后 3 根应该是 16007, 16008, 16009
        assertThat(result.get(2).close()).isEqualByComparingTo(BigDecimal.valueOf(16009));
    }

    @Test
    @DisplayName("空缓存应返回空列表")
    void shouldReturnEmptyForMissingKey() {
        List<BarEvent> result = cache.getBars(btcUsdt, Timeframe.M1, 10);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("size 应返回正确的缓存数量")
    void shouldReturnCorrectSize() {
        assertThat(cache.size(btcUsdt, Timeframe.M1)).isEqualTo(0);

        cache.addBar(createBar(btcUsdt, Timeframe.M1, 1000L, 16000));
        assertThat(cache.size(btcUsdt, Timeframe.M1)).isEqualTo(1);
    }
}
