package com.tj.crypto.strategy.core;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorRegistry;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认策略上下文实现。
 * 委托给 FactorRegistry 进行因子查询。
 */
@Component
@AllArgsConstructor
public class DefaultStrategyContext implements StrategyContext {

    private final FactorRegistry factorRegistry;

    @Override
    public Factor getFactor(String name, Instrument instrument, Timeframe timeframe) {
        return factorRegistry.calculate(name, instrument, timeframe);
    }

    @Override
    public List<Factor> getAllFactors(Instrument instrument, Timeframe timeframe) {
        return factorRegistry.calculateAll(instrument, timeframe);
    }

    @Override
    public Factor getFactorAsOf(String name, Instrument instrument, Timeframe timeframe,
                                long asOfTimestamp) {
        return factorRegistry.calculateAsOf(name, instrument, timeframe, asOfTimestamp);
    }

    @Override
    public List<Factor> getAllFactorsAsOf(Instrument instrument, Timeframe timeframe,
                                          long asOfTimestamp) {
        return factorRegistry.calculateAllAsOf(instrument, timeframe, asOfTimestamp);
    }
}
