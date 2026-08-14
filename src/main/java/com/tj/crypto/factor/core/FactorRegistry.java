package com.tj.crypto.factor.core;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.tj.crypto.marketdata.model.BarEvent;
import java.util.stream.Collectors;

/**
 * 因子注册表。
 * 注册所有 FactorCalculator Bean，提供统一的因子查询和计算接口。
 */
@Slf4j
@Component
public class FactorRegistry {

    private final Map<String, FactorCalculator> calculators = new HashMap<>();

    public FactorRegistry(List<FactorCalculator> calculatorList) {
        for (FactorCalculator calc : calculatorList) {
            calculators.put(calc.name(), calc);
            log.info("Registered factor calculator: {}", calc.name());
        }
        log.info("FactorRegistry initialized with {} calculators", calculators.size());
    }

    /**
     * 计算指定因子。
     *
     * @param factorName 因子名称
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @return 因子值，未找到计算器时返回 null
     */
    public Factor calculate(String factorName, Instrument instrument, Timeframe timeframe) {
        FactorCalculator calc = calculators.get(factorName);
        if (calc == null) {
            log.warn("Factor calculator not found: {}", factorName);
            return null;
        }
        return calc.calculate(instrument, timeframe);
    }

    /** 使用显式历史切片计算，供回测与因子研究使用。 */
    public Factor calculate(String factorName, Instrument instrument, Timeframe timeframe,
                            List<BarEvent> bars) {
        FactorCalculator calc = calculators.get(factorName);
        if (calc == null) {
            log.warn("Factor calculator not found: {}", factorName);
            return null;
        }
        return calc.calculate(instrument, timeframe, List.copyOf(bars));
    }

    /** 按事件时间读取因子，防止异步或并发行情造成时间穿越。 */
    public Factor calculateAsOf(String factorName, Instrument instrument, Timeframe timeframe,
                                long asOfTimestamp) {
        FactorCalculator calc = calculators.get(factorName);
        if (calc == null) {
            log.warn("Factor calculator not found: {}", factorName);
            return null;
        }
        return calc.calculateAsOf(instrument, timeframe, asOfTimestamp);
    }

    /**
     * 计算所有已注册因子。
     *
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @return 所有因子值列表
     */
    public List<Factor> calculateAll(Instrument instrument, Timeframe timeframe) {
        return calculators.values().stream()
                .map(calc -> {
                    try {
                        return calc.calculate(instrument, timeframe);
                    } catch (Exception e) {
                        log.error("Factor calculation error for {}: {}", calc.name(), e.getMessage(), e);
                        return null;
                    }
                })
                .filter(f -> f != null && f.isUsable())
                .collect(Collectors.toList());
    }

    public List<Factor> calculateAllAsOf(Instrument instrument, Timeframe timeframe, long asOfTimestamp) {
        return calculators.values().stream()
                .map(calc -> {
                    try {
                        return calc.calculateAsOf(instrument, timeframe, asOfTimestamp);
                    } catch (Exception e) {
                        log.error("Factor calculation error for {} as of {}: {}",
                                calc.name(), asOfTimestamp, e.getMessage(), e);
                        return null;
                    }
                })
                .filter(f -> f != null && f.isUsable())
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已注册的因子名称。
     */
    public List<String> getRegisteredFactors() {
        return List.copyOf(calculators.keySet());
    }

    public boolean supportsBarHistory(String factorName) {
        FactorCalculator calculator = calculators.get(factorName);
        return calculator instanceof BarHistoryFactorCalculator;
    }

    public FactorCalculator require(String factorName) {
        FactorCalculator calculator = calculators.get(factorName);
        if (calculator == null) {
            throw new IllegalArgumentException("Unknown factor: " + factorName);
        }
        return calculator;
    }
}
