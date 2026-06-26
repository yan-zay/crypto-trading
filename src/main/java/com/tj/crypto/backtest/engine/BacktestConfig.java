package com.tj.crypto.backtest.engine;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;

import java.math.BigDecimal;

/**
 * 回测配置，不可变值对象。
 *
 * @param instrument      交易工具
 * @param timeframe       时间周期
 * @param startTime       回测起始时间（毫秒）
 * @param endTime         回测结束时间（毫秒）
 * @param initialBalance  初始资金
 */
public record BacktestConfig(
        Instrument instrument,
        Timeframe timeframe,
        long startTime,
        long endTime,
        BigDecimal initialBalance
) {}
