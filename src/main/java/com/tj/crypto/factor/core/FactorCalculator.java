package com.tj.crypto.factor.core;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;

import java.util.List;

/**
 * 因子计算器接口。
 * 每个实现类负责计算一个特定的技术指标或衍生品因子。
 *
 * 设计决策：
 * - 接口而非抽象类，因为因子计算逻辑差异大
 * - calculate() 接受 Instrument + Timeframe，返回单个 Factor
 * - 因子不持有状态，每次调用重新计算（依赖 BarCache）
 */
public interface FactorCalculator {

    /**
     * 因子名称。
     * 如 "SMA_20", "RSI_14", "MACD_HIST", "FUNDING_RATE_CHANGE"。
     */
    String name();

    /**
     * 计算因子值。
     *
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @return 因子值（包含质量标记）
     */
    Factor calculate(Instrument instrument, Timeframe timeframe);

    /**
     * 使用调用方提供的、截至某一历史时点的 finalized bar 切片计算。
     *
     * <p>不依赖 bar 的因子可以沿用默认实现；所有 bar 型因子必须覆盖此方法，
     * 以保证回测和研究不会意外读取实时全局缓存。
     */
    default Factor calculate(Instrument instrument, Timeframe timeframe, List<BarEvent> bars) {
        return calculate(instrument, timeframe);
    }

    /**
     * 从计算器自己的数据源读取不晚于 asOfTimestamp 的状态。
     */
    default Factor calculateAsOf(Instrument instrument, Timeframe timeframe, long asOfTimestamp) {
        return calculate(instrument, timeframe);
    }
}
