package com.tj.crypto.backtest.report;

import com.tj.crypto.backtest.portfolio.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 性能计算器。
 * 从交易记录计算各项性能指标。
 */
@Slf4j
@Component
public class PerformanceCalculator {

    private static final int SCALE = 6;
    private static final int HIGH_SCALE = 12;
    private static final BigDecimal RISK_FREE_RATE_ANNUAL = BigDecimal.valueOf(0.02);
    private static final long MILLIS_PER_YEAR = 365L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 计算性能报告。
     *
     * @param trades         交易记录
     * @param initialBalance 初始资金
     * @param finalBalance   最终资金
     * @param startTime      回测起始时间
     * @param endTime        回测结束时间
     * @return 性能报告
     */
    public PerformanceReport calculate(List<Trade> trades, BigDecimal initialBalance,
                                        BigDecimal finalBalance, long startTime, long endTime) {
        return calculate(trades, initialBalance, finalBalance, startTime, endTime, "{}");
    }

    /**
     * 计算性能报告（含假设快照）。
     *
     * @param trades         交易记录
     * @param initialBalance 初始资金
     * @param finalBalance   最终资金
     * @param startTime      回测起始时间
     * @param endTime        回测结束时间
     * @param assumptionsJson 回测假设快照（JSON 字符串）
     * @return 性能报告
     */
    public PerformanceReport calculate(List<Trade> trades, BigDecimal initialBalance,
                                        BigDecimal finalBalance, long startTime, long endTime,
                                        String assumptionsJson) {
        return calculate(trades, initialBalance, finalBalance, startTime, endTime,
                assumptionsJson, List.of());
    }

    /** 使用净值曲线计算时序风险指标。 */
    public PerformanceReport calculate(List<Trade> trades, BigDecimal initialBalance,
                                        BigDecimal finalBalance, long startTime, long endTime,
                                        String assumptionsJson, List<EquityPoint> equityCurve) {
        if (trades.isEmpty()) {
            return emptyReport(initialBalance, finalBalance, startTime, endTime, assumptionsJson);
        }

        int totalTrades = trades.size();
        int winningTrades = 0;
        int losingTrades = 0;
        BigDecimal totalWin = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        int maxConsecutiveLosses = 0;
        int currentConsecutiveLosses = 0;
        int maxWinStreak = 0;
        int maxLoseStreak = 0;
        int currentWinStreak = 0;
        long totalDuration = 0;
        BigDecimal totalFees = BigDecimal.ZERO;

        List<BigDecimal> perTradeReturns = new ArrayList<>();

        for (Trade trade : trades) {
            BigDecimal netPnl = trade.netPnL();
            // 累计手续费
            if (trade.totalFee() != null) {
                totalFees = totalFees.add(trade.totalFee());
            }
            // 交易时长
            totalDuration += (trade.exitTime() - trade.entryTime());

            // 每笔交易收益率 = PnL / (entryPrice * quantity)
            BigDecimal notional = trade.entryPrice().multiply(trade.quantity());
            if (notional.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal tradeReturn = netPnl
                        .divide(notional, HIGH_SCALE, RoundingMode.HALF_UP);
                perTradeReturns.add(tradeReturn);
            }

            if (netPnl.compareTo(BigDecimal.ZERO) > 0) {
                winningTrades++;
                totalWin = totalWin.add(netPnl);
                currentConsecutiveLosses = 0;
                currentWinStreak++;
                maxWinStreak = Math.max(maxWinStreak, currentWinStreak);
            } else {
                losingTrades++;
                totalLoss = totalLoss.add(netPnl.abs());
                currentWinStreak = 0;
                currentConsecutiveLosses++;
                maxConsecutiveLosses = Math.max(maxConsecutiveLosses, currentConsecutiveLosses);
                maxLoseStreak = Math.max(maxLoseStreak, currentConsecutiveLosses);
            }
        }

        // 总收益率
        BigDecimal totalReturn = finalBalance.subtract(initialBalance)
                .divide(initialBalance, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // 胜率
        BigDecimal winRate = BigDecimal.valueOf(winningTrades)
                .divide(BigDecimal.valueOf(totalTrades), SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // 平均盈利/亏损
        BigDecimal avgWin = winningTrades > 0
                ? totalWin.divide(BigDecimal.valueOf(winningTrades), SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal avgLoss = losingTrades > 0
                ? totalLoss.divide(BigDecimal.valueOf(losingTrades), SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 盈亏比（无亏损时设为 999.99，避免极端值）
        BigDecimal profitFactor = totalLoss.compareTo(BigDecimal.ZERO) > 0
                ? totalWin.divide(totalLoss, SCALE, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(999.99);

        // 最大回撤
        BigDecimal maxDrawdown = calculateMaxDrawdown(trades, initialBalance, equityCurve);

        // 平均交易时长
        BigDecimal avgTradeDuration = BigDecimal.valueOf(totalDuration / totalTrades);

        // 年化收益率
        long durationMillis = endTime - startTime;
        BigDecimal annualizedReturn = calculateAnnualizedReturn(totalReturn, durationMillis);

        List<BigDecimal> periodicReturns = equityCurve == null || equityCurve.size() < 2
                ? perTradeReturns : calculateDailyReturns(equityCurve);
        BigDecimal sharpeRatio = calculateSharpe(periodicReturns,
                equityCurve != null && equityCurve.size() >= 2);
        BigDecimal sortinoRatio = calculateSortino(periodicReturns,
                equityCurve != null && equityCurve.size() >= 2);

        // 卡玛比率
        BigDecimal calmarRatio = calculateCalmar(annualizedReturn, maxDrawdown);

        // 月度收益
        Map<String, BigDecimal> monthlyReturns = calculateMonthlyReturns(
                trades, initialBalance, equityCurve);

        return new PerformanceReport(
                totalReturn, maxDrawdown, winRate,
                totalTrades, winningTrades, losingTrades,
                avgWin, avgLoss, profitFactor, maxConsecutiveLosses,
                initialBalance, finalBalance, startTime, endTime,
                annualizedReturn, sharpeRatio, sortinoRatio, calmarRatio,
                avgTradeDuration, maxWinStreak, maxLoseStreak, monthlyReturns,
                totalFees, assumptionsJson
        );
    }

    private BigDecimal calculateAnnualizedReturn(BigDecimal totalReturnPct, long durationMillis) {
        if (durationMillis <= 0) {
            return BigDecimal.ZERO;
        }
        // annReturn = ((1 + totalReturn/100) ^ (millisPerYear / duration) - 1) * 100
        double totalReturnDecimal = totalReturnPct.doubleValue() / 100.0;
        double factor = (double) MILLIS_PER_YEAR / durationMillis;
        double base = 1.0 + totalReturnDecimal;
        if (base <= 0) {
            return BigDecimal.valueOf(-100);
        }
        double annReturn = (Math.pow(base, factor) - 1.0) * 100.0;
        if (!Double.isFinite(annReturn)) {
            // Overflow: cap at a large but representable value
            return new BigDecimal("999999999999.999999");
        }
        return BigDecimal.valueOf(annReturn).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSharpe(List<BigDecimal> returns, boolean dailySeries) {
        if (returns.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal meanReturn = mean(returns);
        BigDecimal stdReturn = std(returns, meanReturn);

        if (stdReturn.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal riskFreePeriod = dailySeries
                ? RISK_FREE_RATE_ANNUAL.divide(BigDecimal.valueOf(365), HIGH_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal ratio = meanReturn.subtract(riskFreePeriod)
                .divide(stdReturn, HIGH_SCALE, RoundingMode.HALF_UP);
        return dailySeries ? ratio.multiply(BigDecimal.valueOf(Math.sqrt(365)))
                .setScale(SCALE, RoundingMode.HALF_UP) : ratio.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSortino(List<BigDecimal> returns, boolean dailySeries) {
        if (returns.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal meanReturn = mean(returns);
        BigDecimal downsideStd = downsideStd(returns, BigDecimal.ZERO);

        if (downsideStd.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal riskFreePeriod = dailySeries
                ? RISK_FREE_RATE_ANNUAL.divide(BigDecimal.valueOf(365), HIGH_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal ratio = meanReturn.subtract(riskFreePeriod)
                .divide(downsideStd, HIGH_SCALE, RoundingMode.HALF_UP);
        return dailySeries ? ratio.multiply(BigDecimal.valueOf(Math.sqrt(365)))
                .setScale(SCALE, RoundingMode.HALF_UP) : ratio.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateCalmar(BigDecimal annualizedReturn, BigDecimal maxDrawdown) {
        if (maxDrawdown.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return annualizedReturn.divide(maxDrawdown, SCALE, RoundingMode.HALF_UP);
    }

    private Map<String, BigDecimal> calculateMonthlyReturns(List<Trade> trades,
                                                             BigDecimal initialBalance,
                                                             List<EquityPoint> equityCurve) {
        if (equityCurve != null && !equityCurve.isEmpty()) {
            Map<String, List<BigDecimal>> grouped = new TreeMap<>();
            for (EquityPoint point : equityCurve) {
                String month = Instant.ofEpochMilli(point.timestamp())
                        .atZone(ZoneId.of("UTC")).format(MONTH_FMT);
                grouped.computeIfAbsent(month, ignored -> new ArrayList<>()).add(point.equity());
            }
            Map<String, BigDecimal> returns = new TreeMap<>();
            grouped.forEach((month, values) -> {
                BigDecimal first = values.get(0);
                BigDecimal last = values.get(values.size() - 1);
                BigDecimal value = first.signum() == 0 ? BigDecimal.ZERO
                        : last.subtract(first).divide(first, HIGH_SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                returns.put(month, value.setScale(SCALE, RoundingMode.HALF_UP));
            });
            return returns;
        }
        Map<String, BigDecimal> monthlyPnl = new TreeMap<>();
        for (Trade trade : trades) {
            String month = Instant.ofEpochMilli(trade.exitTime())
                    .atZone(ZoneId.of("UTC"))
                    .format(MONTH_FMT);
            monthlyPnl.merge(month, trade.netPnL(), BigDecimal::add);
        }
        Map<String, BigDecimal> returns = new TreeMap<>();
        monthlyPnl.forEach((month, pnl) -> returns.put(month,
                pnl.divide(initialBalance, HIGH_SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(SCALE, RoundingMode.HALF_UP)));
        return returns;
    }

    private BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), HIGH_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal std(List<BigDecimal> values, BigDecimal mean) {
        BigDecimal sumSqDiff = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal diff = v.subtract(mean);
            sumSqDiff = sumSqDiff.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSqDiff.divide(BigDecimal.valueOf(values.size()), HIGH_SCALE, RoundingMode.HALF_UP);
        // sqrt via double
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(HIGH_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal downsideStd(List<BigDecimal> values, BigDecimal mean) {
        BigDecimal sumSqDiff = BigDecimal.ZERO;
        int count = 0;
        for (BigDecimal v : values) {
            if (v.compareTo(mean) < 0) {
                BigDecimal diff = v.subtract(mean);
                sumSqDiff = sumSqDiff.add(diff.multiply(diff));
                count++;
            }
        }
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        // Semi-variance: average of squared downside deviations
        BigDecimal variance = sumSqDiff.divide(BigDecimal.valueOf(values.size()), HIGH_SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(HIGH_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMaxDrawdown(List<Trade> trades, BigDecimal initialBalance,
                                            List<EquityPoint> equityCurve) {
        if (equityCurve != null && !equityCurve.isEmpty()) {
            return calculateMaxDrawdownFromEquity(equityCurve);
        }
        BigDecimal peak = initialBalance;
        BigDecimal equity = initialBalance;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (Trade trade : trades) {
            equity = equity.add(trade.netPnL());
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal drawdown = peak.subtract(equity)
                    .divide(peak, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown;
    }

    private BigDecimal calculateMaxDrawdownFromEquity(List<EquityPoint> equityCurve) {
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (EquityPoint point : equityCurve.stream()
                .sorted(java.util.Comparator.comparingLong(EquityPoint::timestamp)).toList()) {
            BigDecimal equity = point.equity();
            if (equity.compareTo(peak) > 0) peak = equity;
            if (peak.signum() <= 0) continue;
            BigDecimal drawdown = peak.subtract(equity)
                    .divide(peak, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
        }
        return maxDrawdown;
    }

    private List<BigDecimal> calculateDailyReturns(List<EquityPoint> equityCurve) {
        Map<Long, BigDecimal> dailyClose = new TreeMap<>();
        long dayMillis = 86_400_000L;
        equityCurve.stream()
                .sorted(java.util.Comparator.comparingLong(EquityPoint::timestamp))
                .forEach(point -> dailyClose.put(point.timestamp() / dayMillis, point.equity()));
        List<BigDecimal> values = new ArrayList<>(dailyClose.values());
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 1; i < values.size(); i++) {
            BigDecimal previous = values.get(i - 1);
            if (previous.signum() != 0) {
                returns.add(values.get(i).subtract(previous)
                        .divide(previous, HIGH_SCALE, RoundingMode.HALF_UP));
            }
        }
        return returns;
    }

    private PerformanceReport emptyReport(BigDecimal initialBalance, BigDecimal finalBalance,
                                          long startTime, long endTime, String assumptionsJson) {
        BigDecimal totalReturn = finalBalance.subtract(initialBalance)
                .divide(initialBalance, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return new PerformanceReport(
                totalReturn, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                initialBalance, finalBalance, startTime, endTime,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, Map.of(),
                BigDecimal.ZERO, assumptionsJson
        );
    }
}
