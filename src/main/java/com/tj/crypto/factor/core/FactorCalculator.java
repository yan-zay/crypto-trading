package com.tj.crypto.factor.core;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;

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
}
