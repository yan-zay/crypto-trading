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
        TradeRecordDO DO = new TradeRecordDO();
        DO.setExchange(trade.instrument().exchange().getCode());
        DO.setSymbol(trade.instrument().symbol());
        DO.setSide(trade.side().name());
        DO.setQuantity(trade.quantity());
        DO.setEntryPrice(trade.entryPrice());
        DO.setExitPrice(trade.exitPrice());
        DO.setEntryTime(trade.entryTime());
        DO.setExitTime(trade.exitTime());
        DO.setRealizedPnl(trade.realizedPnL());
        return DO;
    }
}
