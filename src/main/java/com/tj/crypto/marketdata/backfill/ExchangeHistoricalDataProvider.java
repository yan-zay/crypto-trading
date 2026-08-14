package com.tj.crypto.marketdata.backfill;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.common.domain.Exchange;

/** Historical candle provider owned by one external data platform. */
public interface ExchangeHistoricalDataProvider extends HistoricalDataProvider {
    Exchange exchange();
}
