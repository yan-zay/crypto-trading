package com.tj.crypto.backtest.robustness;

import com.tj.crypto.backtest.portfolio.Trade;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/** Deterministic trade-return bootstrap and non-normal Sharpe diagnostics. */
@Component
public class BacktestRobustnessAnalyzer {
    private static final int BOOTSTRAP_SAMPLES = 2_000;

    public BacktestRobustnessReport analyze(List<Trade> trades, long seed) {
        double[] returns = trades.stream().mapToDouble(this::returnOf).toArray();
        List<String> warnings = new ArrayList<>();
        if (returns.length < 30) warnings.add("Fewer than 30 closed trades; inference is low power");
        if (returns.length < 2) {
            warnings.add("Variance-based statistics require at least two trades");
            return new BacktestRobustnessReport(returns.length, 0, seed, zero(), zero(), zero(),
                    zero(), zero(), zero(), zero(), zero(), zero(), "INSUFFICIENT", List.copyOf(warnings));
        }

        double mean = mean(returns);
        double deviation = standardDeviation(returns, mean);
        double skew = skewness(returns, mean, deviation);
        double excessKurtosis = excessKurtosis(returns, mean, deviation);
        double[] bootstrapMeans = bootstrapMeans(returns, seed);
        Arrays.sort(bootstrapMeans);
        double lower = quantile(bootstrapMeans, 0.025);
        double upper = quantile(bootstrapMeans, 0.975);
        long positive = Arrays.stream(bootstrapMeans).filter(value -> value > 0).count();
        double probabilityPositive = (double) positive / bootstrapMeans.length;
        double periodSharpe = deviation == 0 ? 0 : mean / deviation;
        double psr = probabilisticSharpe(periodSharpe, skew, excessKurtosis + 3, returns.length);
        double minimumTrackRecord = minimumTrackRecord(periodSharpe, skew,
                excessKurtosis + 3, 1.645);
        if (deviation == 0) warnings.add("All trade returns are identical; Sharpe evidence is undefined");
        if (Math.abs(skew) > 1) warnings.add("Trade returns are strongly skewed");
        if (excessKurtosis > 3) warnings.add("Trade returns have heavy tails");
        warnings.add("Use DeflatedSharpeAnalyzer with the complete registered trial family; it is not inferred from one run");
        warnings.add(RobustnessStatistics.FORWARD_VALIDATION_LIMITATION);
        String grade = evidenceGrade(returns.length, psr, lower);
        return new BacktestRobustnessReport(returns.length, BOOTSTRAP_SAMPLES, seed,
                decimal(mean), decimal(deviation), decimal(skew), decimal(excessKurtosis),
                decimal(lower), decimal(upper), decimal(probabilityPositive), decimal(psr),
                decimal(minimumTrackRecord), grade, List.copyOf(warnings));
    }

    private double returnOf(Trade trade) {
        BigDecimal notional = trade.entryPrice().multiply(trade.quantity());
        return notional.signum() == 0 ? 0 : trade.netPnL()
                .divide(notional, 18, RoundingMode.HALF_UP).doubleValue();
    }

    private double[] bootstrapMeans(double[] values, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        double[] samples = new double[BOOTSTRAP_SAMPLES];
        for (int sample = 0; sample < samples.length; sample++) {
            double total = 0;
            for (int i = 0; i < values.length; i++) total += values[random.nextInt(values.length)];
            samples[sample] = total / values.length;
        }
        return samples;
    }

    private double mean(double[] values) {
        return Arrays.stream(values).average().orElse(0);
    }

    private double standardDeviation(double[] values, double mean) {
        double sum = 0;
        for (double value : values) sum += Math.pow(value - mean, 2);
        return Math.sqrt(sum / (values.length - 1));
    }

    private double skewness(double[] values, double mean, double deviation) {
        if (deviation == 0 || values.length < 3) return 0;
        double sum = 0;
        for (double value : values) sum += Math.pow((value - mean) / deviation, 3);
        return values.length * sum / ((values.length - 1D) * (values.length - 2D));
    }

    private double excessKurtosis(double[] values, double mean, double deviation) {
        if (deviation == 0 || values.length < 4) return 0;
        double sum = 0;
        for (double value : values) sum += Math.pow((value - mean) / deviation, 4);
        double n = values.length;
        return n * (n + 1) * sum / ((n - 1) * (n - 2) * (n - 3))
                - 3 * Math.pow(n - 1, 2) / ((n - 2) * (n - 3));
    }

    private double probabilisticSharpe(double sharpe, double skew, double kurtosis, int observations) {
        if (observations < 2) return 0;
        double variance = 1 - skew * sharpe + ((kurtosis - 1) / 4D) * sharpe * sharpe;
        if (variance <= 0) return 0;
        double statistic = sharpe * Math.sqrt(observations - 1D) / Math.sqrt(variance);
        return normalCdf(statistic);
    }

    private double minimumTrackRecord(double sharpe, double skew, double kurtosis, double z) {
        if (sharpe <= 0) return Double.POSITIVE_INFINITY;
        double variance = 1 - skew * sharpe + ((kurtosis - 1) / 4D) * sharpe * sharpe;
        return Math.max(1, 1 + variance * Math.pow(z / sharpe, 2));
    }

    private double normalCdf(double value) {
        double x = Math.abs(value);
        double t = 1 / (1 + 0.2316419 * x);
        double density = 0.3989422804014327 * Math.exp(-x * x / 2);
        double probability = 1 - density * t * (0.319381530 + t * (-0.356563782
                + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return value >= 0 ? probability : 1 - probability;
    }

    private double quantile(double[] sorted, double probability) {
        double index = probability * (sorted.length - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted[lower];
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower);
    }

    private String evidenceGrade(int observations, double psr, double lowerBound) {
        if (observations < 30) return "INSUFFICIENT";
        if (observations >= 100 && psr >= 0.95 && lowerBound > 0) return "STRONG";
        if (psr >= 0.80 && lowerBound >= 0) return "MODERATE";
        return "WEAK";
    }

    private BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) return new BigDecimal("999999");
        return BigDecimal.valueOf(value).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}
