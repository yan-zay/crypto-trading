package com.tj.crypto.backtest.data;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;

import java.util.List;

/**
 * 历史数据提供者接口。
 * 加载历史 BarEvent 数据，供回测使用。
 */
public interface HistoricalDataProvider {

    /**
     * 加载历史 Bar 数据。
     *
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @param from       起始时间（毫秒）
     * @param to         结束时间（毫秒）
     * @return 按时间正序排列的 BarEvent 列表
     */
    List<BarEvent> loadBars(Instrument instrument, Timeframe timeframe, long from, long to);
}
