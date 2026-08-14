package com.tj.crypto.strategy.analysis;

import com.tj.crypto.backtest.data.InMemoryHistoricalDataProvider;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestEngine;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.report.PerformanceCalculator;
import com.tj.crypto.backtest.report.PerformanceReport;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketRegime;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import com.tj.crypto.strategy.core.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Canonical strategy research evaluator.
 *
 * <p>All evaluations run through BacktestEngine. This keeps factor warm-up, next-bar fills,
 * fees, slippage and account semantics identical to normal backtests.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEvaluator {

    private static final int REGIME_LOOKBACK = 20;
    private static final int HIGH_SCALE = 12;
    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(10_000);

    private final BarEventMapper barEventMapper;
    private final List<Strategy> strategies;
    private final PerformanceCalculator performanceCalculator;
    private final BacktestEngine backtestEngine;

    public Map<MarketRegime, PerformanceReport> evaluateByRegime(
            String strategyName, String symbol, int days) {
        List<BarEvent> bars = fetchBars(defaultInstrument(symbol), Timeframe.H1,
                Instant.now().minusSeconds(days * 86_400L).toEpochMilli(),
                Instant.now().toEpochMilli());
        if (bars.size() <= REGIME_LOOKBACK) return Map.of();

        BacktestResult result = runCanonical(strategyName, bars);
        NavigableMap<Long, MarketRegime> regimes = classifyRegimes(bars);
        Map<MarketRegime, List<Trade>> grouped = new EnumMap<>(MarketRegime.class);
        for (Trade trade : result.trades()) {
            Map.Entry<Long, MarketRegime> entry = regimes.floorEntry(trade.entryTime());
            if (entry != null) grouped.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(trade);
        }

        Map<MarketRegime, PerformanceReport> reports = new LinkedHashMap<>();
        grouped.forEach((regime, trades) -> {
            BigDecimal finalBalance = trades.stream()
                    .map(Trade::netPnL).reduce(INITIAL_BALANCE, BigDecimal::add);
            long start = trades.stream().mapToLong(Trade::entryTime).min().orElse(result.config().startTime());
            long end = trades.stream().mapToLong(Trade::exitTime).max().orElse(result.config().endTime());
            reports.put(regime, performanceCalculator.calculate(
                    trades, INITIAL_BALANCE, finalBalance, start, end,
                    result.performanceReport().assumptionsJson()));
        });
        return reports;
    }

    public ParameterStabilityReport calculateParameterStability(
            String strategyName, String symbol, Map<String, double[]> paramRanges) {
        long end = Instant.now().toEpochMilli();
        List<BarEvent> bars = fetchBars(defaultInstrument(symbol), Timeframe.H1,
                end - 30L * 86_400_000L, end);
        if (bars.size() <= REGIME_LOOKBACK) {
            return new ParameterStabilityReport(strategyName, symbol, 0, 0, Map.of());
        }

        Strategy template = requireStrategy(strategyName);
        List<Double> allReturns = new ArrayList<>();
        Map<String, List<Double>> perParameter = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> range : paramRanges.entrySet()) {
            List<Double> returns = new ArrayList<>();
            for (double value : range.getValue()) {
                Strategy candidate = freshStrategy(template);
                if (!setNumericField(candidate, range.getKey(), value)) {
                    log.warn("Strategy {} has no mutable numeric parameter '{}'",
                            strategyName, range.getKey());
                    break;
                }
                BacktestResult result = runCanonical(candidate, bars);
                double totalReturn = result.performanceReport().totalReturn().doubleValue();
                returns.add(totalReturn);
                allReturns.add(totalReturn);
            }
            perParameter.put(range.getKey(), List.copyOf(returns));
        }
        double mean = allReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new ParameterStabilityReport(
                strategyName, symbol, mean, sampleStd(allReturns, mean), perParameter);
    }

    public double calculateStrategyCorrelation(
            String firstStrategy, String secondStrategy, String symbol, int days) {
        long end = Instant.now().toEpochMilli();
        long start = end - (long) days * 86_400_000L;
        List<BarEvent> bars = fetchBars(defaultInstrument(symbol), Timeframe.H1, start, end);
        if (bars.size() <= REGIME_LOOKBACK) return 0;

        BacktestResult first = runCanonical(firstStrategy, bars);
        BacktestResult second = runCanonical(secondStrategy, bars);
        List<BigDecimal> firstReturns = dailyReturns(first.trades(), start, end);
        List<BigDecimal> secondReturns = dailyReturns(second.trades(), start, end);
        return pearson(firstReturns, secondReturns);
    }

    private BacktestResult runCanonical(String strategyName, List<BarEvent> bars) {
        return runCanonical(freshStrategy(requireStrategy(strategyName)), bars);
    }

    private BacktestResult runCanonical(Strategy strategy, List<BarEvent> bars) {
        Instrument instrument = bars.get(0).instrument();
        Timeframe timeframe = bars.get(0).timeframe();
        long dataStart = bars.get(0).metadata().exchangeTimestamp();
        long tradeStart = bars.get(Math.min(REGIME_LOOKBACK, bars.size() - 1))
                .metadata().exchangeTimestamp();
        long end = bars.get(bars.size() - 1).metadata().exchangeTimestamp();
        BacktestConfig config = new BacktestConfig(
                instrument, timeframe, dataStart, tradeStart, end, INITIAL_BALANCE);
        return backtestEngine.run(config, strategy, new InMemoryHistoricalDataProvider(bars));
    }

    private Strategy requireStrategy(String name) {
        return strategies.stream().filter(strategy -> strategy.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + name));
    }

    private Strategy freshStrategy(Strategy template) {
        try {
            var constructor = template.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            return (Strategy) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Strategy must expose a no-arg constructor for isolated evaluation: "
                            + template.getClass().getName(), e);
        }
    }

    private List<BarEvent> fetchBars(Instrument instrument, Timeframe timeframe, long from, long to) {
        return barEventMapper.selectByTimeRange(
                        instrument.exchange().getCode(), instrument.marketType().getCode(),
                        instrument.symbol(), timeframe.getCode(), from, to).stream()
                .map(this::toBarEvent).toList();
    }

    private BarEvent toBarEvent(BarEventDO source) {
        Exchange exchange = Exchange.valueOf(source.getExchange().toUpperCase());
        Instrument instrument = Instrument.of(exchange,
                MarketType.valueOf(source.getMarketType().toUpperCase()), source.getSymbol());
        return new BarEvent(instrument, EventMetadata.of(exchange, source.getOpenTime()),
                Timeframe.fromCode(source.getTimeframe()), source.getOpenPrice(), source.getHighPrice(),
                source.getLowPrice(), source.getClosePrice(), source.getVolume(), source.getQuoteVolume(), true);
    }

    private Instrument defaultInstrument(String symbol) {
        return Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, symbol);
    }

    private NavigableMap<Long, MarketRegime> classifyRegimes(List<BarEvent> bars) {
        NavigableMap<Long, MarketRegime> regimes = new TreeMap<>();
        for (int i = REGIME_LOOKBACK; i < bars.size(); i++) {
            regimes.put(bars.get(i).metadata().exchangeTimestamp(),
                    classifyRegime(bars.subList(i - REGIME_LOOKBACK, i + 1)));
        }
        return regimes;
    }

    private MarketRegime classifyRegime(List<BarEvent> window) {
        double first = window.get(0).close().doubleValue();
        double last = window.get(window.size() - 1).close().doubleValue();
        double change = first == 0 ? 0 : (last - first) / first;
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < window.size(); i++) {
            double previous = window.get(i - 1).close().doubleValue();
            if (previous != 0) returns.add((window.get(i).close().doubleValue() - previous) / previous);
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double volatility = Math.sqrt(returns.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0));
        if (volatility > 0.02) return MarketRegime.HIGH_VOLATILITY;
        if (volatility < 0.005) return MarketRegime.LOW_VOLATILITY;
        if (change > 0.02) return MarketRegime.TRENDING_UP;
        if (change < -0.02) return MarketRegime.TRENDING_DOWN;
        return MarketRegime.RANGING;
    }

    private boolean setNumericField(Object target, String fieldName, double value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            if (Modifier.isFinal(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) return false;
            field.setAccessible(true);
            if (field.getType() == int.class || field.getType() == Integer.class) field.set(target, (int) value);
            else if (field.getType() == long.class || field.getType() == Long.class) field.set(target, (long) value);
            else if (field.getType() == double.class || field.getType() == Double.class) field.set(target, value);
            else if (field.getType() == BigDecimal.class) field.set(target, BigDecimal.valueOf(value));
            else return false;
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private List<BigDecimal> dailyReturns(List<Trade> trades, long start, long end) {
        Map<Long, BigDecimal> pnlByDay = new HashMap<>();
        long day = 86_400_000L;
        trades.forEach(trade -> pnlByDay.merge(
                trade.exitTime() / day, trade.netPnL(), BigDecimal::add));
        List<BigDecimal> result = new ArrayList<>();
        for (long timestamp = start; timestamp <= end; timestamp += day) {
            result.add(pnlByDay.getOrDefault(timestamp / day, BigDecimal.ZERO)
                    .divide(INITIAL_BALANCE, HIGH_SCALE, RoundingMode.HALF_UP));
        }
        return result;
    }

    private double pearson(List<BigDecimal> first, List<BigDecimal> second) {
        int size = Math.min(first.size(), second.size());
        if (size < 2) return 0;
        double meanFirst = first.stream().limit(size).mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double meanSecond = second.stream().limit(size).mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double covariance = 0, varianceFirst = 0, varianceSecond = 0;
        for (int i = 0; i < size; i++) {
            double x = first.get(i).doubleValue() - meanFirst;
            double y = second.get(i).doubleValue() - meanSecond;
            covariance += x * y;
            varianceFirst += x * x;
            varianceSecond += y * y;
        }
        double denominator = Math.sqrt(varianceFirst * varianceSecond);
        return denominator == 0 ? 0 : covariance / denominator;
    }

    private double sampleStd(List<Double> values, double mean) {
        if (values.size() < 2) return 0;
        double squares = values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum();
        return Math.sqrt(squares / (values.size() - 1));
    }

    public record ParameterStabilityReport(
            String strategyName,
            String symbol,
            double meanReturn,
            double returnStdDev,
            Map<String, List<Double>> perParameterReturns
    ) {}
}
