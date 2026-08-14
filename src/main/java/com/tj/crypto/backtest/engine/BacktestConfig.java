package com.tj.crypto.backtest.engine;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;

import java.math.BigDecimal;

/**
 * 回测配置，不可变值对象。
 *
 * @param instrument      交易工具
 * @param timeframe       时间周期
 * @param dataStartTime   数据回放起始时间（含指标预热区间）
 * @param startTime       允许产生信号和订单的交易起始时间
 * @param endTime         回测结束时间（毫秒）
 * @param initialBalance  初始资金
 */
public record BacktestConfig(
        Instrument instrument,
        Timeframe timeframe,
        long dataStartTime,
        long startTime,
        long endTime,
        BigDecimal initialBalance
) {
    public BacktestConfig {
        if (dataStartTime > startTime) {
            throw new IllegalArgumentException("dataStartTime must be <= startTime");
        }
        if (startTime > endTime) {
            throw new IllegalArgumentException("startTime must be <= endTime");
        }
        if (initialBalance == null || initialBalance.signum() <= 0) {
            throw new IllegalArgumentException("initialBalance must be positive");
        }
    }

    /** 无独立预热区间的兼容构造函数。 */
    public BacktestConfig(Instrument instrument, Timeframe timeframe, long startTime,
                          long endTime, BigDecimal initialBalance) {
        this(instrument, timeframe, startTime, startTime, endTime, initialBalance);
    }
}
