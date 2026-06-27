package com.tj.crypto.factor.analysis;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.factor.cache.BarCache;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.model.BarEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 因子分析器。
 * 提供因子评估的三大核心指标：信息系数（IC）、信号命中率、因子收益统计。
 *
 * <p>依赖 BarCache 获取历史 bar 数据，依赖 FactorRegistry 计算因子值。
 * IC 使用 Pearson 相关系数（因子值 vs 前向收益）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactorAnalyzer {

    private static final int SCALE = 6;
    private static final int HIGH_SCALE = 12;
    private static final int DEFAULT_BAR_COUNT = 200;
    private static final MathContext MC = MathContext.DECIMAL128;

    private final BarCache barCache;
    private final FactorRegistry factorRegistry;

    /**
     * 计算信息系数（IC）。
     * IC = Pearson(factorValues, forwardReturns)。
     *
     * @param factorName 因子名称
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @param days       回溯天数（决定 bar 数量）
     * @return IC 值（-1 到 1），数据不足时返回 NaN
     */
    public double calculateIC(String factorName, Instrument instrument, Timeframe timeframe, int days) {
        int barCount = daysToBarCount(days, timeframe);
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, barCount);

        if (bars.size() < 3) {
            log.warn("Insufficient bars for IC calculation: {} bars", bars.size());
            return Double.NaN;
        }

        List<BigDecimal> factorValues = computeFactorValues(factorName, instrument, timeframe, bars);
        List<BigDecimal> forwardReturns = computeForwardReturns(bars);

        int n = Math.min(factorValues.size(), forwardReturns.size());
        if (n < 2) {
            return Double.NaN;
        }

        return pearsonCorrelation(
                factorValues.subList(0, n),
                forwardReturns.subList(0, n)
        );
    }

    /**
     * 计算信号命中率。
     * 命中率 = factor 和 forwardReturn 同号的比例。
     *
     * @param factorName 因子名称
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @param days       回溯天数
     * @param threshold  阈值（未使用，预留用于绝对值过滤）
     * @return 命中率（0 到 1），数据不足时返回 NaN
     */
    public double calculateHitRate(String factorName, Instrument instrument, Timeframe timeframe,
                                   int days, double threshold) {
        int barCount = daysToBarCount(days, timeframe);
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, barCount);

        if (bars.size() < 3) {
            log.warn("Insufficient bars for hit rate calculation: {} bars", bars.size());
            return Double.NaN;
        }

        List<BigDecimal> factorValues = computeFactorValues(factorName, instrument, timeframe, bars);
        List<BigDecimal> forwardReturns = computeForwardReturns(bars);

        int n = Math.min(factorValues.size(), forwardReturns.size());
        if (n == 0) {
            return Double.NaN;
        }

        int hits = 0;
        for (int i = 0; i < n; i++) {
            boolean factorPositive = factorValues.get(i).compareTo(BigDecimal.ZERO) > 0;
            boolean returnPositive = forwardReturns.get(i).compareTo(BigDecimal.ZERO) > 0;
            if (factorPositive == returnPositive) {
                hits++;
            }
        }

        return (double) hits / n;
    }

    /**
     * 计算因子收益统计。
     * 将样本按因子正/负分组，计算各组平均收益及 t 检验。
     *
     * @param factorName 因子名称
     * @param instrument 交易工具
     * @param timeframe  时间周期
     * @param days       回溯天数
     * @return 因子收益统计
     */
    public FactorReturnStats calculateFactorReturns(String factorName, Instrument instrument,
                                                     Timeframe timeframe, int days) {
        int barCount = daysToBarCount(days, timeframe);
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, barCount);

        if (bars.size() < 3) {
            return new FactorReturnStats(factorName,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 1.0);
        }

        List<BigDecimal> factorValues = computeFactorValues(factorName, instrument, timeframe, bars);
        List<BigDecimal> forwardReturns = computeForwardReturns(bars);

        int n = Math.min(factorValues.size(), forwardReturns.size());
        List<BigDecimal> positiveReturns = new ArrayList<>();
        List<BigDecimal> negativeReturns = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (factorValues.get(i).compareTo(BigDecimal.ZERO) > 0) {
                positiveReturns.add(forwardReturns.get(i));
            } else if (factorValues.get(i).compareTo(BigDecimal.ZERO) < 0) {
                negativeReturns.add(forwardReturns.get(i));
            }
        }

        BigDecimal avgPositive = mean(positiveReturns);
        BigDecimal avgNegative = mean(negativeReturns);
        double[] tTest = welchTTest(positiveReturns, negativeReturns);

        return new FactorReturnStats(factorName, avgPositive, avgNegative, tTest[0], tTest[1]);
    }

    // ==================== 内部计算方法 ====================

    /**
     * 计算每根 bar 对应的因子值。
     * 从第 2 根 bar 开始计算（需要至少 1 根历史 bar 作为因子计算上下文）。
     */
    private List<BigDecimal> computeFactorValues(String factorName, Instrument instrument,
                                                  Timeframe timeframe, List<BarEvent> bars) {
        List<BigDecimal> values = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            Factor factor = factorRegistry.calculate(factorName, instrument, timeframe);
            if (factor != null && factor.isUsable()) {
                values.add(factor.value());
            } else {
                values.add(BigDecimal.ZERO);
            }
        }
        return values;
    }

    /**
     * 计算前向收益：close[i+1] / close[i] - 1。
     * 返回 bars.size() - 1 个值。
     */
    private List<BigDecimal> computeForwardReturns(List<BarEvent> bars) {
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 0; i < bars.size() - 1; i++) {
            BigDecimal currentClose = bars.get(i).close();
            BigDecimal nextClose = bars.get(i + 1).close();
            if (currentClose.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal ret = nextClose.subtract(currentClose)
                        .divide(currentClose, HIGH_SCALE, RoundingMode.HALF_UP);
                returns.add(ret);
            } else {
                returns.add(BigDecimal.ZERO);
            }
        }
        return returns;
    }

    /**
     * Welch's t-test（不等方差 t 检验）。
     *
     * @return [tStat, pValue]
     */
    private double[] welchTTest(List<BigDecimal> sample1, List<BigDecimal> sample2) {
        if (sample1.isEmpty() || sample2.isEmpty()) {
            return new double[]{0.0, 1.0};
        }

        int n1 = sample1.size();
        int n2 = sample2.size();
        double mean1 = mean(sample1).doubleValue();
        double mean2 = mean(sample2).doubleValue();
        double var1 = variance(sample1, mean1);
        double var2 = variance(sample2, mean2);

        double se = Math.sqrt(var1 / n1 + var2 / n2);
        if (se == 0.0) {
            return new double[]{0.0, 1.0};
        }

        double tStat = (mean1 - mean2) / se;

        // Welch-Satterthwaite 自由度
        double num = Math.pow(var1 / n1 + var2 / n2, 2);
        double den = Math.pow(var1 / n1, 2) / (n1 - 1) + Math.pow(var2 / n2, 2) / (n2 - 1);
        double df = den > 0 ? num / den : 1.0;

        double pValue = tTestPValue(Math.abs(tStat), df);
        return new double[]{tStat, pValue};
    }

    /**
     * 双侧 p 值近似（Abramowitz & Stegun 近似）。
     */
    private double tTestPValue(double absT, double df) {
        double x = df / (df + absT * absT);
        double p = betaIncomplete(df / 2.0, 0.5, x);
        return Math.min(1.0, Math.max(0.0, p));
    }

    /**
     * 不完全 Beta 函数的近似（正则化）。
     * 使用连分数展开近似。
     */
    private double betaIncomplete(double a, double b, double x) {
        if (x < 0 || x > 1) {
            return 0.0;
        }
        if (x == 0 || x == 1) {
            return x;
        }

        double lnBeta = lnGamma(a) + lnGamma(b) - lnGamma(a + b);
        double front = Math.exp(Math.log(x) * a + Math.log(1 - x) * b - lnBeta);

        // 连分数展开（Lentz 算法）
        double f = 1.0;
        double c = 1.0;
        double d = 1.0 - (a + b) * x / (a + 1);
        if (Math.abs(d) < 1e-30) {
            d = 1e-30;
        }
        d = 1.0 / d;
        f = d;

        for (int i = 1; i <= 200; i++) {
            double m = i;
            double numerator;

            // 偶数步
            numerator = m * (b - m) * x / ((a + 2 * m - 1) * (a + 2 * m));
            d = 1.0 + numerator * d;
            if (Math.abs(d) < 1e-30) {
                d = 1e-30;
            }
            d = 1.0 / d;
            c = 1.0 + numerator / c;
            if (Math.abs(c) < 1e-30) {
                c = 1e-30;
            }
            f *= d * c;

            // 奇数步
            numerator = -(a + m) * (a + b + m) * x / ((a + 2 * m) * (a + 2 * m + 1));
            d = 1.0 + numerator * d;
            if (Math.abs(d) < 1e-30) {
                d = 1e-30;
            }
            d = 1.0 / d;
            c = 1.0 + numerator / c;
            if (Math.abs(c) < 1e-30) {
                c = 1e-30;
            }
            double delta = d * c;
            f *= delta;

            if (Math.abs(delta - 1.0) < 1e-8) {
                break;
            }
        }

        return front * f / a;
    }

    /**
     * ln(Gamma(x)) 的 Lanczos 近似。
     */
    private double lnGamma(double x) {
        double[] coeff = {
                76.18009172947146, -86.50532032941677,
                24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            ser += coeff[j] / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
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

        if (denominator == 0) {
            return 0.0;
        }
        return numerator / denominator;
    }

    private BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), HIGH_SCALE, RoundingMode.HALF_UP);
    }

    private double variance(List<BigDecimal> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }
        double sumSq = 0;
        for (BigDecimal v : values) {
            double diff = v.doubleValue() - mean;
            sumSq += diff * diff;
        }
        return sumSq / (values.size() - 1);
    }

    private int daysToBarCount(int days, Timeframe timeframe) {
        long millisPerDay = 86_400_000L;
        long totalMillis = (long) days * millisPerDay;
        return (int) (totalMillis / timeframe.getMillis()) + 1;
    }
}
