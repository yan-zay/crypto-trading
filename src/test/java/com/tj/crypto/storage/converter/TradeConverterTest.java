package com.tj.crypto.storage.converter;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.storage.entity.TradeRecordDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TradeConverterTest {

    @Test
    @DisplayName("Trade → TradeRecordDO 转换应正确")
    void shouldConvertToDO() {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        Trade trade = new Trade(instrument, OrderSide.LONG,
                BigDecimal.valueOf(0.1), BigDecimal.valueOf(16000), BigDecimal.valueOf(17000),
                1000L, 2000L, BigDecimal.valueOf(100));

        TradeRecordDO DO = TradeConverter.toDO(trade);

        assertThat(DO.getExchange()).isEqualTo("binance");
        assertThat(DO.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(DO.getSide()).isEqualTo("LONG");
        assertThat(DO.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(0.1));
        assertThat(DO.getEntryPrice()).isEqualByComparingTo(BigDecimal.valueOf(16000));
        assertThat(DO.getExitPrice()).isEqualByComparingTo(BigDecimal.valueOf(17000));
        assertThat(DO.getEntryTime()).isEqualTo(1000L);
        assertThat(DO.getExitTime()).isEqualTo(2000L);
        assertThat(DO.getRealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }
}
