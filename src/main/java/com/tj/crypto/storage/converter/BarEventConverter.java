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
        BarEventDO dobj = new BarEventDO();
        dobj.setExchange(event.instrument().exchange().getCode());
        dobj.setMarketType(event.instrument().marketType().getCode());
        dobj.setSymbol(event.instrument().symbol());
        dobj.setTimeframe(event.timeframe().getCode());
        dobj.setOpenTime(event.metadata().exchangeTimestamp());
        dobj.setOpenPrice(event.open());
        dobj.setHighPrice(event.high());
        dobj.setLowPrice(event.low());
        dobj.setClosePrice(event.close());
        dobj.setVolume(event.volume());
        dobj.setQuoteVolume(event.quoteVolume());
        return dobj;
    }

    /**
     * BarEventDO → BarEvent。
     */
    public static BarEvent toEvent(BarEventDO dobj) {
        Exchange exchange = Exchange.valueOf(dobj.getExchange().toUpperCase());
        MarketType marketType = MarketType.valueOf(dobj.getMarketType().toUpperCase());
        com.tj.crypto.common.domain.Instrument instrument =
                com.tj.crypto.common.domain.Instrument.of(exchange, marketType, dobj.getSymbol());
        Timeframe timeframe = Timeframe.fromCode(dobj.getTimeframe());
        com.tj.crypto.marketdata.model.EventMetadata metadata =
                com.tj.crypto.marketdata.model.EventMetadata.of(exchange, dobj.getOpenTime());

        return new BarEvent(instrument, metadata, timeframe,
                dobj.getOpenPrice(), dobj.getHighPrice(), dobj.getLowPrice(), dobj.getClosePrice(),
                dobj.getVolume(), dobj.getQuoteVolume(), true);
    }
}
