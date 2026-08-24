package com.tj.crypto.marketdata.archive;

import com.tj.crypto.marketdata.model.ArchivableMarketData;
import com.tj.crypto.marketdata.model.FundingRateEvent;
import com.tj.crypto.marketdata.model.IndexPriceEvent;
import com.tj.crypto.marketdata.model.L2OrderBookEvent;
import com.tj.crypto.marketdata.model.MarkPriceEvent;
import com.tj.crypto.marketdata.model.OpenInterestEvent;
import com.tj.crypto.marketdata.model.RawMarketMessage;
import com.tj.crypto.marketdata.model.TradeEvent;

/** 稳定的归档事件类型；枚举名进入 Parquet 和 manifest，不得静默重命名。 */
public enum ArchiveEventType {
    TRADE(TradeEvent.class, "trade"),
    L2_ORDER_BOOK(L2OrderBookEvent.class, "l2"),
    MARK_PRICE(MarkPriceEvent.class, "mark_price"),
    INDEX_PRICE(IndexPriceEvent.class, "index_price"),
    FUNDING_RATE(FundingRateEvent.class, "funding"),
    OPEN_INTEREST(OpenInterestEvent.class, "open_interest"),
    RAW(RawMarketMessage.class, "raw");

    private final Class<? extends ArchivableMarketData> eventClass;
    private final String partitionValue;

    ArchiveEventType(Class<? extends ArchivableMarketData> eventClass, String partitionValue) {
        this.eventClass = eventClass;
        this.partitionValue = partitionValue;
    }

    public Class<? extends ArchivableMarketData> eventClass() {
        return eventClass;
    }

    public String partitionValue() {
        return partitionValue;
    }

    public static ArchiveEventType from(ArchivableMarketData event) {
        if (event == null) throw new ArchiveValidationException("NULL_EVENT", "archive event is required");
        for (ArchiveEventType type : values()) {
            if (type.eventClass.equals(event.getClass())) return type;
        }
        throw new ArchiveValidationException("UNSUPPORTED_EVENT_TYPE",
                "unsupported market event type: " + event.getClass().getName());
    }
}
