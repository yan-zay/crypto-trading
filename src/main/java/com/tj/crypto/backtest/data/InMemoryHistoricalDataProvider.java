package com.tj.crypto.backtest.data;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存历史数据提供者。
 * 用于测试和小规模回测。
 */
public class InMemoryHistoricalDataProvider implements HistoricalDataProvider {

    private final List<BarEvent> allBars;

    public InMemoryHistoricalDataProvider(List<BarEvent> bars) {
        this.allBars = new ArrayList<>(bars);
    }

    @Override
    public List<BarEvent> loadBars(Instrument instrument, Timeframe timeframe, long from, long to) {
        return allBars.stream()
                .filter(bar -> bar.instrument().equals(instrument))
                .filter(bar -> bar.timeframe() == timeframe)
                .filter(bar -> bar.metadata().exchangeTimestamp() >= from)
                .filter(bar -> bar.metadata().exchangeTimestamp() <= to)
                .toList();
    }
}
