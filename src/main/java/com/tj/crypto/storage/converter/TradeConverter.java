package com.tj.crypto.storage.converter;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.storage.entity.TradeRecordDO;

/**
 * Trade ↔ TradeRecordDO 转换器。
 */
public final class TradeConverter {

    private TradeConverter() {}

    /**
     * Trade → TradeRecordDO。
     */
    public static TradeRecordDO toDO(Trade trade) {
        TradeRecordDO dobj = new TradeRecordDO();
        dobj.setExchange(trade.instrument().exchange().getCode());
        dobj.setMarketType(trade.instrument().marketType().name());
        dobj.setSymbol(trade.instrument().symbol());
        dobj.setSide(trade.side().name());
        dobj.setQuantity(trade.quantity());
        dobj.setEntryPrice(trade.entryPrice());
        dobj.setExitPrice(trade.exitPrice());
        dobj.setEntryTime(trade.entryTime());
        dobj.setExitTime(trade.exitTime());
        dobj.setRealizedPnl(trade.realizedPnL());
        dobj.setTotalFee(trade.totalFee());
        dobj.setNetPnl(trade.netPnL());
        return dobj;
    }
}
