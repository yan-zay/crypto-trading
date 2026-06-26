package com.tj.crypto.backtest.report;

import com.tj.crypto.backtest.portfolio.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 性能计算器。
 * 从交易记录计算各项性能指标。
 */
@Slf4j
@Component
public class PerformanceCalculator {

    private static final int SCALE = 6;

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
        if (trades.isEmpty()) {
            return emptyReport(initialBalance, startTime, endTime);
        }

        int totalTrades = trades.size();
        int winningTrades = 0;
        int losingTrades = 0;
        BigDecimal totalWin = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        int maxConsecutiveLosses = 0;
        int currentConsecutiveLosses = 0;

        for (Trade trade : trades) {
            if (trade.isProfitable()) {
                winningTrades++;
                totalWin = totalWin.add(trade.realizedPnL());
                currentConsecutiveLosses = 0;
            } else {
                losingTrades++;
                totalLoss = totalLoss.add(trade.realizedPnL().abs());
                currentConsecutiveLosses++;
                maxConsecutiveLosses = Math.max(maxConsecutiveLosses, currentConsecutiveLosses);
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

        // 最大回撤（简化计算：基于交易序列）
        BigDecimal maxDrawdown = calculateMaxDrawdown(trades, initialBalance);

        return new PerformanceReport(
                totalReturn, maxDrawdown, winRate,
                totalTrades, winningTrades, losingTrades,
                avgWin, avgLoss, profitFactor, maxConsecutiveLosses,
                initialBalance, finalBalance, startTime, endTime
        );
    }

    private BigDecimal calculateMaxDrawdown(List<Trade> trades, BigDecimal initialBalance) {
        BigDecimal peak = initialBalance;
        BigDecimal equity = initialBalance;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (Trade trade : trades) {
            equity = equity.add(trade.realizedPnL());
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

    private PerformanceReport emptyReport(BigDecimal initialBalance, long startTime, long endTime) {
        return new PerformanceReport(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                initialBalance, initialBalance, startTime, endTime
        );
    }
}
