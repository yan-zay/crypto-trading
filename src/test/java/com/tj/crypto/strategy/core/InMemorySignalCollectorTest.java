package com.tj.crypto.strategy.core;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySignalCollectorTest {

    private InMemorySignalCollector collector;
    private Instrument btcUsdt;

    @BeforeEach
    void setUp() {
        collector = new InMemorySignalCollector();
        btcUsdt = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }

    private SignalEvent createSignal(String strategy, SignalType type, long timestamp) {
        return new SignalEvent(strategy, btcUsdt, type, BigDecimal.ONE, "test", Map.of(), timestamp);
    }

    @Test
    @DisplayName("收集信号后可查询到")
    void shouldCollectAndRetrieve() {
        SignalEvent signal = createSignal("S1", SignalType.BUY, 1000L);
        collector.collect(signal);

        List<SignalEvent> result = collector.getSignals("S1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(SignalType.BUY);
    }

    @Test
    @DisplayName("不同策略的信号应独立存储")
    void shouldStoreSeparatelyByStrategy() {
        collector.collect(createSignal("S1", SignalType.BUY, 1000L));
        collector.collect(createSignal("S2", SignalType.SELL, 2000L));

        assertThat(collector.getSignals("S1")).hasSize(1);
        assertThat(collector.getSignals("S2")).hasSize(1);
    }

    @Test
    @DisplayName("按时间范围查询应正确过滤")
    void shouldFilterByTimeRange() {
        collector.collect(createSignal("S1", SignalType.BUY, 1000L));
        collector.collect(createSignal("S1", SignalType.SELL, 2000L));
        collector.collect(createSignal("S1", SignalType.BUY, 3000L));

        List<SignalEvent> result = collector.getSignals("S1", 1500L, 2500L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).timestamp()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("clear 应清空所有信号")
    void shouldClearAll() {
        collector.collect(createSignal("S1", SignalType.BUY, 1000L));
        collector.clear();

        assertThat(collector.getSignals("S1")).isEmpty();
    }

    @Test
    @DisplayName("不存在的策略应返回空列表")
    void shouldReturnEmptyForMissingStrategy() {
        assertThat(collector.getSignals("nonexistent")).isEmpty();
    }
}
