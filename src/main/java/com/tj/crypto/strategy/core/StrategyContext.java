package com.tj.crypto.strategy.core;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorRegistry;

import java.util.List;

/**
 * 策略上下文接口。
 * 为策略提供因子查询能力，策略不直接依赖 FactorRegistry。
 *
 * 设计决策：
 * - 接口而非直接注入 FactorRegistry，方便测试时 mock
 * - 只暴露查询方法，不暴露注册/修改方法
 */
public interface StrategyContext {

    /**
     * 查询指定因子。
     */
    Factor getFactor(String name, Instrument instrument, Timeframe timeframe);

    /**
     * 查询所有已注册因子。
     */
    List<Factor> getAllFactors(Instrument instrument, Timeframe timeframe);

    /** 按事件时点查询因子；默认实现兼容不支持历史快照的测试上下文。 */
    default Factor getFactorAsOf(String name, Instrument instrument, Timeframe timeframe,
                                 long asOfTimestamp) {
        return getFactor(name, instrument, timeframe);
    }

    /** 按事件时点查询全部因子。 */
    default List<Factor> getAllFactorsAsOf(Instrument instrument, Timeframe timeframe,
                                           long asOfTimestamp) {
        return getAllFactors(instrument, timeframe);
    }

    /**
     * 将当前上下文冻结到指定事件时间。策略仍使用原有 getFactor API，
     * 但实际读取的是该时点快照。
     */
    default StrategyContext at(long asOfTimestamp) {
        StrategyContext delegate = this;
        return new StrategyContext() {
            @Override
            public Factor getFactor(String name, Instrument instrument, Timeframe timeframe) {
                return delegate.getFactorAsOf(name, instrument, timeframe, asOfTimestamp);
            }

            @Override
            public List<Factor> getAllFactors(Instrument instrument, Timeframe timeframe) {
                return delegate.getAllFactorsAsOf(instrument, timeframe, asOfTimestamp);
            }
        };
    }
}
