package com.tj.crypto.storage.converter;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.storage.entity.BarEventDO;

/**
 * BarEvent ↔ BarEventDO 转换器。
 */
public final class BarEventConverter {

    private BarEventConverter() {}

    /**
     * BarEvent → BarEventDO。
     */
    public static BarEventDO toDO(BarEvent event) {
        BarEventDO DO = new BarEventDO();
        DO.setExchange(event.instrument().exchange().getCode());
        DO.setMarketType(event.instrument().marketType().getCode());
        DO.setSymbol(event.instrument().symbol());
        DO.setTimeframe(event.timeframe().getCode());
        DO.setOpenTime(event.metadata().exchangeTimestamp());
        DO.setOpenPrice(event.open());
        DO.setHighPrice(event.high());
        DO.setLowPrice(event.low());
        DO.setClosePrice(event.close());
        DO.setVolume(event.volume());
        DO.setQuoteVolume(event.quoteVolume());
        return DO;
    }

    /**
     * BarEventDO → BarEvent。
     */
    public static BarEvent toEvent(BarEventDO DO) {
        Exchange exchange = Exchange.valueOf(DO.getExchange().toUpperCase());
        MarketType marketType = MarketType.valueOf(DO.getMarketType().toUpperCase());
        com.tj.crypto.common.domain.Instrument instrument =
                com.tj.crypto.common.domain.Instrument.of(exchange, marketType, DO.getSymbol());
        Timeframe timeframe = Timeframe.fromCode(DO.getTimeframe());
        com.tj.crypto.marketdata.model.EventMetadata metadata =
                com.tj.crypto.marketdata.model.EventMetadata.of(exchange, DO.getOpenTime());

        return new BarEvent(instrument, metadata, timeframe,
                DO.getOpenPrice(), DO.getHighPrice(), DO.getLowPrice(), DO.getClosePrice(),
                DO.getVolume(), DO.getQuoteVolume(), true);
    }
}
