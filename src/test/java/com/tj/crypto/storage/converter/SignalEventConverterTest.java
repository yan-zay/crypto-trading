package com.tj.crypto.storage.converter;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.storage.entity.SignalEventDO;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignalEventConverterTest {

    @Test
    @DisplayName("SignalEvent → SignalEventDO 转换应正确")
    void shouldConvertToDO() {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        SignalEvent event = new SignalEvent(
                "MacdCross", instrument, SignalType.BUY,
                BigDecimal.valueOf(0.7), "MACD 金叉",
                Map.of("MACD_HIST", BigDecimal.valueOf(0.5)),
                1672515780000L);

        SignalEventDO DO = SignalEventConverter.toDO(event);

        assertThat(DO.getStrategyName()).isEqualTo("MacdCross");
        assertThat(DO.getExchange()).isEqualTo("binance");
        assertThat(DO.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(DO.getSignalType()).isEqualTo("buy");
        assertThat(DO.getConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.7));
        assertThat(DO.getReason()).isEqualTo("MACD 金叉");
        assertThat(DO.getFactorSnapshot()).contains("MACD_HIST");
        assertThat(DO.getSignalTime()).isEqualTo(1672515780000L);
    }

    @Test
    @DisplayName("空 factorSnapshot 应正常处理")
    void shouldHandleEmptyFactorSnapshot() {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        SignalEvent event = new SignalEvent(
                "Test", instrument, SignalType.SELL,
                BigDecimal.ONE, "test", Map.of(), 1000L);

        SignalEventDO DO = SignalEventConverter.toDO(event);

        assertThat(DO.getFactorSnapshot()).isNull();
    }
}
