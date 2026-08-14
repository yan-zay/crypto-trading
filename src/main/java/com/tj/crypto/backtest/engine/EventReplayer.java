package com.tj.crypto.backtest.engine;

import com.tj.crypto.backtest.data.HistoricalDataProvider;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 事件回放器。
 * 从历史数据加载 BarEvent 并按时间顺序发布到 MarketEventBus。
 *
 * 设计决策：
 * - 同步发布（回测模式下确保事件顺序确定性）
 * - 每个事件发布后等待处理完成（通过同步事件总线实现）
 */
@Slf4j
public class EventReplayer {

    private final MarketEventBus eventBus;

    public EventReplayer(MarketEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 回放历史事件。
     *
     * @param provider   历史数据提供者
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @param from       起始时间
     * @param to         结束时间
     * @return 回放的事件数量
     */
    public int replay(HistoricalDataProvider provider, Instrument instrument,
                      Timeframe timeframe, long from, long to) {
        List<BarEvent> bars = provider.loadBars(instrument, timeframe, from, to);
        log.info("Replaying {} bars for {} {} [{}, {}]",
                bars.size(), instrument.symbol(), timeframe.getCode(), from, to);

        int count = 0;
        BacktestProgressMonitor monitor = BacktestExecutionContext.monitor();
        for (BarEvent bar : bars) {
            monitor.checkpoint();
            eventBus.publish(bar);
            count++;
            monitor.onProgress(count, bars.size());
        }

        log.info("Replay complete: {} events published", count);
        return count;
    }
}
