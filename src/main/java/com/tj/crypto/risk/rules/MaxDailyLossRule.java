package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskProperties;
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

    private final RiskProperties riskProperties;

    public MaxDailyLossRule(RiskProperties riskProperties) {
        this.riskProperties = riskProperties;
    }

    @Override
    public String name() {
        return "MaxDailyLoss";
    }

    @Override
    public RiskCheckResult check(Order order, TradingAccount account) {
        // 使用订单时间而非系统时间，支持回测场景
        long now = order.createdAt();
        long todayStart = getDayStartMillis(now);
        BigDecimal maxDailyLossPct = riskProperties.getMaxDailyLossPct();

        BigDecimal dailyLoss = account.getTrades().stream()
                .filter(t -> t.exitTime() >= todayStart && t.exitTime() <= now && t.netPnL().compareTo(BigDecimal.ZERO) < 0)
                .map(Trade::netPnL)
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

    /**
     * 计算指定时间所在自然日的起始时间（UTC 00:00:00）。
     * 使用 24 小时滚动窗口近似，避免依赖时区。
     */
    private long getDayStartMillis(long now) {
        // 24 小时滚动窗口，适用于回测和实盘
        long dayMillis = 24L * 60 * 60 * 1000;
        return (now / dayMillis) * dayMillis;
    }
}
