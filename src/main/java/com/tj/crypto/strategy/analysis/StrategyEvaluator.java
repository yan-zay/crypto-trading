package com.tj.crypto.strategy.analysis;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.*;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 策略评估器。
 * 提供策略在不同市场状态下的表现评估、参数稳定性分析、策略相关性分析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEvaluator {

    private static final int SCALE = 6;
    private static final int HIGH_SCALE = 12;
    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(10000);
    private static final BigDecimal TRADE_QUANTITY = BigDecimal.ONE;

    private final BarEventMapper barEventMapper;
    private final List<Strategy> strategies;
    private final PerformanceCalculator performanceCalculator;

    /**
     * 按市场状态评估策略表现。
     * 将历史数据按市场状态分组，分别回测并生成性能报告。
     *
     * @param strategyName 策略名称
     * @param symbol       交易对符号（如 "BTCUSDT"）
     * @param days         回溯天数
     * @return 各市场状态下的性能报告
     */
    public Map<MarketRegime, PerformanceReport> evaluateByRegime(String strategyName, String symbol, int days) {
        Strategy strategy = findStrategy(strategyName);
        if (strategy == null) {
            log.warn("Strategy not found: {}", strategyName);
            return Map.of();
        }

        long endTime = Instant.now().toEpochMilli();
        long startTime = endTime - (long) days * 86_400_000L;
        List<BarEvent> bars = fetchBars(symbol, Timeframe.H1, startTime, endTime);

        if (bars.size() < 20) {
            log.warn("Insufficient bars for regime evaluation: {} bars", bars.size());
            return Map.of();
        }

        Instrument instrument = bars.get(0).instrument();
        Map<MarketRegime, List<BarEvent>> regimeGroups = classifyBarsByRegime(bars);

        Map<MarketRegime, PerformanceReport> results = new LinkedHashMap<>();
        for (Map.Entry<MarketRegime, List<BarEvent>> entry : regimeGroups.entrySet()) {
            MarketRegime regime = entry.getKey();
            List<BarEvent> regimeBars = entry.getValue();
            if (regimeBars.size() < 5) {
                continue;
            }
            List<SignalEvent> signals = replayBacktest(strategy, instrument, regimeBars);
            List<Trade> trades = convertSignalsToTrades(signals);
            PerformanceReport report = performanceCalculator.calculate(
                    trades, INITIAL_BALANCE, INITIAL_BALANCE,
                    regimeBars.get(0).metadata().exchangeTimestamp(),
                    regimeBars.get(regimeBars.size() - 1).metadata().exchangeTimestamp()
            );
            results.put(regime, report);
        }
        return results;
    }

    /**
     * 计算参数稳定性。
     * 对每个参数进行网格搜索，测量策略收益的标准差。
     *
     * @param strategyName 策略名称
     * @param symbol       交易对符号
     * @param paramRanges  参数范围（key = 参数名, value = 测试值数组）
     * @return 参数稳定性报告
     */
    public ParameterStabilityReport calculateParameterStability(String strategyName, String symbol,
                                                                  Map<String, double[]> paramRanges) {
        Strategy strategy = findStrategy(strategyName);
        if (strategy == null) {
            return new ParameterStabilityReport(strategyName, symbol, 0.0, 0.0, Map.of());
        }

        long endTime = Instant.now().toEpochMilli();
        long startTime = endTime - 30L * 86_400_000L;
        List<BarEvent> bars = fetchBars(symbol, Timeframe.H1, startTime, endTime);

        if (bars.size() < 10) {
            return new ParameterStabilityReport(strategyName, symbol, 0.0, 0.0, Map.of());
        }

        // 记录默认参数值
        Map<String, Object> defaultParams = new HashMap<>();
        for (String paramName : paramRanges.keySet()) {
            Object defaultVal = getFieldValue(strategy, paramName);
            if (defaultVal != null) {
                defaultParams.put(paramName, defaultVal);
            }
        }

        List<Double> allReturns = new ArrayList<>();
        Map<String, List<Double>> perParamReturns = new LinkedHashMap<>();

        for (Map.Entry<String, double[]> entry : paramRanges.entrySet()) {
            String paramName = entry.getKey();
            double[] values = entry.getValue();
            List<Double> paramReturns = new ArrayList<>();

            for (double value : values) {
                try {
                    setFieldValue(strategy, paramName, value);
                    List<SignalEvent> signals = replayBacktest(strategy, bars.get(0).instrument(), bars);
                    List<Trade> trades = convertSignalsToTrades(signals);
                    PerformanceReport report = performanceCalculator.calculate(
                            trades, INITIAL_BALANCE, INITIAL_BALANCE,
                            bars.get(0).metadata().exchangeTimestamp(),
                            bars.get(bars.size() - 1).metadata().exchangeTimestamp()
                    );
                    double totalReturn = report.totalReturn().doubleValue();
                    paramReturns.add(totalReturn);
                    allReturns.add(totalReturn);
                } catch (Exception e) {
                    log.warn("Parameter stability test failed for {}={}: {}", paramName, value, e.getMessage());
                }
            }
            perParamReturns.put(paramName, paramReturns);

            // 恢复默认值
            Object defaultVal = defaultParams.get(paramName);
            if (defaultVal != null) {
                double dv = convertToDouble(defaultVal);
                setFieldValue(strategy, paramName, dv);
            }
        }

        double meanReturn = allReturns.stream().mapToDouble(d -> d).average().orElse(0.0);
        double stdReturn = std(allReturns, meanReturn);
        return new ParameterStabilityReport(strategyName, symbol, meanReturn, stdReturn, perParamReturns);
    }

    /**
     * 计算两个策略的收益相关性。
     *
     * @param strategy1 策略 1 名称
     * @param strategy2 策略 2 名称
     * @param symbol    交易对符号
     * @param days      回溯天数
     * @return Pearson 相关系数（-1 到 1）
     */
    public double calculateStrategyCorrelation(String strategy1, String strategy2, String symbol, int days) {
        Strategy s1 = findStrategy(strategy1);
        Strategy s2 = findStrategy(strategy2);
        if (s1 == null || s2 == null) {
            return 0.0;
        }

        long endTime = Instant.now().toEpochMilli();
        long startTime = endTime - (long) days * 86_400_000L;
        List<BarEvent> bars = fetchBars(symbol, Timeframe.H1, startTime, endTime);

        if (bars.size() < 10) {
            return 0.0;
        }

        Instrument instrument = bars.get(0).instrument();
        List<Trade> trades1 = convertSignalsToTrades(replayBacktest(s1, instrument, bars));
        List<Trade> trades2 = convertSignalsToTrades(replayBacktest(s2, instrument, bars));

        List<BigDecimal> returns1 = extractDailyReturns(trades1, startTime, endTime);
        List<BigDecimal> returns2 = extractDailyReturns(trades2, startTime, endTime);

        int n = Math.min(returns1.size(), returns2.size());
        if (n < 2) {
            return 0.0;
        }
        return pearsonCorrelation(returns1.subList(0, n), returns2.subList(0, n));
    }

    // ==================== 内部辅助方法 ====================

    private Strategy findStrategy(String name) {
        return strategies.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 从数据库获取历史 bar 数据。
     */
    private List<BarEvent> fetchBars(String symbol, Timeframe timeframe, long startTime, long endTime) {
        List<BarEventDO> barDOs = barEventMapper.selectByTimeRange(symbol, timeframe.getCode(), startTime, endTime);
        return barDOs.stream()
                .map(this::convertToBarEvent)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 回放 bar 事件，收集策略输出的信号。
     */
    private List<SignalEvent> replayBacktest(Strategy strategy, Instrument instrument, List<BarEvent> bars) {
        List<SignalEvent> signals = new ArrayList<>();
        StrategyContext context = new NoOpStrategyContext();

        for (BarEvent bar : bars) {
            try {
                SignalEvent signal = strategy.onEvent(bar, context);
                if (signal != null && signal.type() != SignalType.HOLD) {
                    signals.add(signal);
                }
            } catch (Exception e) {
                log.debug("Strategy {} error on bar {}: {}", strategy.name(),
                        bar.metadata().exchangeTimestamp(), e.getMessage());
            }
        }
        return signals;
    }

    /**
     * 将信号序列转换为交易记录。
     * BUY 信号开多仓，SELL 信号平仓。
     */
    private List<Trade> convertSignalsToTrades(List<SignalEvent> signals) {
        List<Trade> trades = new ArrayList<>();
        SignalEvent pendingBuy = null;

        for (SignalEvent signal : signals) {
            if (signal.type() == SignalType.BUY && pendingBuy == null) {
                pendingBuy = signal;
            } else if (signal.type() == SignalType.SELL && pendingBuy != null) {
                BigDecimal entryPrice = pendingBuy.factorSnapshot().values().stream()
                        .findFirst().orElse(BigDecimal.ONE);
                BigDecimal exitPrice = signal.factorSnapshot().values().stream()
                        .findFirst().orElse(entryPrice);
                BigDecimal pnl = exitPrice.subtract(entryPrice).multiply(TRADE_QUANTITY);

                trades.add(new Trade(
                        signal.instrument(), OrderSide.LONG, TRADE_QUANTITY,
                        entryPrice, exitPrice,
                        pendingBuy.timestamp(), signal.timestamp(), pnl
                ));
                pendingBuy = null;
            }
        }
        return trades;
    }

    /**
     * 将 bar 数据按市场状态分类。
     */
    private Map<MarketRegime, List<BarEvent>> classifyBarsByRegime(List<BarEvent> bars) {
        Map<MarketRegime, List<BarEvent>> groups = new LinkedHashMap<>();
        int lookback = Math.min(20, bars.size());

        for (int i = lookback; i < bars.size(); i++) {
            List<BarEvent> window = bars.subList(i - lookback, i + 1);
            MarketRegime regime = classifyRegime(window);
            groups.computeIfAbsent(regime, k -> new ArrayList<>()).add(bars.get(i));
        }
        return groups;
    }

    /**
     * 基于价格趋势和波动率对 bar 窗口进行市场状态分类。
     */
    private MarketRegime classifyRegime(List<BarEvent> window) {
        if (window.size() < 2) {
            return MarketRegime.RANGING;
        }

        double firstPrice = window.get(0).close().doubleValue();
        double lastPrice = window.get(window.size() - 1).close().doubleValue();
        double priceChange = (lastPrice - firstPrice) / firstPrice;

        // 计算波动率（收益率标准差）
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < window.size(); i++) {
            double prev = window.get(i - 1).close().doubleValue();
            double curr = window.get(i).close().doubleValue();
            if (prev != 0) {
                returns.add((curr - prev) / prev);
            }
        }
        double meanReturn = returns.stream().mapToDouble(d -> d).average().orElse(0.0);
        double volatility = Math.sqrt(returns.stream()
                .mapToDouble(r -> (r - meanReturn) * (r - meanReturn))
                .average().orElse(0.0));

        if (volatility > 0.02) {
            return MarketRegime.HIGH_VOLATILITY;
        }
        if (volatility < 0.005) {
            return MarketRegime.LOW_VOLATILITY;
        }
        if (priceChange > 0.02) {
            return MarketRegime.TRENDING_UP;
        }
        if (priceChange < -0.02) {
            return MarketRegime.TRENDING_DOWN;
        }
        return MarketRegime.RANGING;
    }

    /**
     * 将 BarEventDO 转换为 BarEvent 领域对象。
     */
    private BarEvent convertToBarEvent(BarEventDO barDO) {
        try {
            Exchange exchange = Exchange.valueOf(barDO.getExchange().toUpperCase());
            MarketType marketType = MarketType.valueOf(barDO.getMarketType().toUpperCase());
            Instrument instrument = Instrument.of(exchange, marketType, barDO.getSymbol());
            Timeframe timeframe = Timeframe.fromCode(barDO.getTimeframe());
            EventMetadata metadata = EventMetadata.of(exchange, barDO.getOpenTime());

            return new BarEvent(instrument, metadata, timeframe,
                    barDO.getOpenPrice(), barDO.getHighPrice(),
                    barDO.getLowPrice(), barDO.getClosePrice(),
                    barDO.getVolume(), barDO.getQuoteVolume(), true);
        } catch (Exception e) {
            log.warn("Failed to convert BarEventDO: {}", e.getMessage());
            return null;
        }
    }

    private List<BigDecimal> extractDailyReturns(List<Trade> trades, long startTime, long endTime) {
        Map<Long, BigDecimal> dailyPnl = new TreeMap<>();
        long dayMillis = 86_400_000L;

        for (Trade trade : trades) {
            long day = (trade.exitTime() / dayMillis) * dayMillis;
            dailyPnl.merge(day, trade.realizedPnL(), BigDecimal::add);
        }

        List<BigDecimal> returns = new ArrayList<>();
        for (long t = startTime; t < endTime; t += dayMillis) {
            BigDecimal pnl = dailyPnl.getOrDefault(t, BigDecimal.ZERO);
            BigDecimal dailyReturn = INITIAL_BALANCE.compareTo(BigDecimal.ZERO) != 0
                    ? pnl.divide(INITIAL_BALANCE, HIGH_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            returns.add(dailyReturn);
        }
        return returns;
    }

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private double convertToDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            return Double.parseDouble(s);
        }
        return 0.0;
    }

    private void setFieldValue(Object obj, String fieldName, double value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == int.class || type == Integer.class) {
                field.set(obj, (int) value);
            } else if (type == long.class || type == Long.class) {
                field.set(obj, (long) value);
            } else if (type == double.class || type == Double.class) {
                field.set(obj, value);
            } else if (type == BigDecimal.class) {
                field.set(obj, BigDecimal.valueOf(value));
            }
        } catch (Exception e) {
            log.debug("Cannot set field {}: {}", fieldName, e.getMessage());
        }
    }

    private double std(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }
        double sumSq = 0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sumSq / (values.size() - 1));
    }

    private double pearsonCorrelation(List<BigDecimal> x, List<BigDecimal> y) {
        int n = x.size();
        if (n < 2) {
            return 0.0;
        }
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            double xi = x.get(i).doubleValue();
            double yi = y.get(i).doubleValue();
            sumX += xi;
            sumY += yi;
            sumXY += xi * yi;
            sumX2 += xi * xi;
            sumY2 += yi * yi;
        }
        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return denominator == 0 ? 0.0 : numerator / denominator;
    }

    // ==================== 内部类型 ====================

    /**
     * 参数稳定性报告。
     */
    public record ParameterStabilityReport(
            String strategyName,
            String symbol,
            double meanReturn,
            double returnStdDev,
            Map<String, List<Double>> perParameterReturns
    ) {}

    /**
     * 空操作策略上下文。
     * 回测模拟时使用，因子查询返回 null（策略需自行处理）。
     */
    private static class NoOpStrategyContext implements StrategyContext {
        @Override
        public com.tj.crypto.factor.core.Factor getFactor(String name, Instrument instrument, Timeframe timeframe) {
            return null;
        }

        @Override
        public List<com.tj.crypto.factor.core.Factor> getAllFactors(Instrument instrument, Timeframe timeframe) {
            return List.of();
        }
    }
}
