package com.tj.crypto.storage.converter;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.storage.entity.BarEventDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BarEventConverterTest {

    @Test
    @DisplayName("BarEvent → BarEventDO 转换应正确")
    void shouldConvertToDO() {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, 1672515780000L);
        BarEvent event = new BarEvent(instrument, metadata, Timeframe.M1,
                BigDecimal.valueOf(16000), BigDecimal.valueOf(16100),
                BigDecimal.valueOf(15900), BigDecimal.valueOf(16050),
                BigDecimal.valueOf(100), BigDecimal.valueOf(1600000), true);

        BarEventDO DO = BarEventConverter.toDO(event);

        assertThat(DO.getExchange()).isEqualTo("binance");
        assertThat(DO.getMarketType()).isEqualTo("perpetual");
        assertThat(DO.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(DO.getTimeframe()).isEqualTo("1m");
        assertThat(DO.getOpenTime()).isEqualTo(1672515780000L);
        assertThat(DO.getOpenPrice()).isEqualByComparingTo(BigDecimal.valueOf(16000));
        assertThat(DO.getClosePrice()).isEqualByComparingTo(BigDecimal.valueOf(16050));
    }

    @Test
    @DisplayName("BarEventDO → BarEvent 转换应正确")
    void shouldConvertToEvent() {
        BarEventDO DO = new BarEventDO();
        DO.setExchange("binance");
        DO.setMarketType("perpetual");
        DO.setSymbol("BTCUSDT");
        DO.setTimeframe("1m");
        DO.setOpenTime(1672515780000L);
        DO.setOpenPrice(BigDecimal.valueOf(16000));
        DO.setHighPrice(BigDecimal.valueOf(16100));
        DO.setLowPrice(BigDecimal.valueOf(15900));
        DO.setClosePrice(BigDecimal.valueOf(16050));
        DO.setVolume(BigDecimal.valueOf(100));
        DO.setQuoteVolume(BigDecimal.valueOf(1600000));

        BarEvent event = BarEventConverter.toEvent(DO);

        assertThat(event.instrument().exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(event.instrument().symbol()).isEqualTo("BTCUSDT");
        assertThat(event.timeframe()).isEqualTo(Timeframe.M1);
        assertThat(event.close()).isEqualByComparingTo(BigDecimal.valueOf(16050));
    }

    @Test
    @DisplayName("双向转换应保持数据一致")
    void shouldRoundTrip() {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "ETHUSDT");
        EventMetadata metadata = EventMetadata.of(Exchange.BINANCE, 1672515780000L);
        BarEvent original = new BarEvent(instrument, metadata, Timeframe.M5,
                BigDecimal.valueOf(1200), BigDecimal.valueOf(1210),
                BigDecimal.valueOf(1190), BigDecimal.valueOf(1205),
                BigDecimal.valueOf(500), BigDecimal.valueOf(600000), true);

        BarEventDO DO = BarEventConverter.toDO(original);
        BarEvent restored = BarEventConverter.toEvent(DO);

        assertThat(restored.instrument().symbol()).isEqualTo(original.instrument().symbol());
        assertThat(restored.timeframe()).isEqualTo(original.timeframe());
        assertThat(restored.open()).isEqualByComparingTo(original.open());
        assertThat(restored.close()).isEqualByComparingTo(original.close());
    }
}
