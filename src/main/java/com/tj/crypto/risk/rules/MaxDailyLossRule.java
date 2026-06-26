package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 每日最大亏损限制。
 * 当日已实现亏损不能超过账户初始余额的指定百分比。
 */
@Component
public class MaxDailyLossRule implements RiskRule {

    /** 最大每日亏损占比（%），默认 5% */
    private BigDecimal maxDailyLossPct = BigDecimal.valueOf(5.0);

    @Override
    public String name() {
        return "MaxDailyLoss";
    }

    @Override
    public RiskCheckResult check(Order order, VirtualAccount account) {
        // 计算当日已实现亏损
        long todayStart = getTodayStartMillis();
        BigDecimal dailyLoss = account.getTrades().stream()
                .filter(t -> t.exitTime() >= todayStart && t.realizedPnL().compareTo(BigDecimal.ZERO) < 0)
                .map(Trade::realizedPnL)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();

        BigDecimal maxLoss = account.getInitialBalance()
                .multiply(maxDailyLossPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        if (dailyLoss.compareTo(maxLoss) >= 0) {
            return RiskCheckResult.rejected(
                    OrderRejectReason.RISK_REJECTED,
                    String.format("当日亏损 $%s 已达限制 $%s (%.1f%%)",
                            dailyLoss.toPlainString(), maxLoss.toPlainString(), maxDailyLossPct)
            );
        }
        return RiskCheckResult.passed();
    }

    private long getTodayStartMillis() {
        // 简化：使用当前时间 - 24小时
        return System.currentTimeMillis() - 24 * 60 * 60 * 1000;
    }

    public void setMaxDailyLossPct(BigDecimal maxDailyLossPct) {
        this.maxDailyLossPct = maxDailyLossPct;
    }
}
