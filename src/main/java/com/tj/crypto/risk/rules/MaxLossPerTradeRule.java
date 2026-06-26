package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.risk.RiskRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单笔最大亏损限制。
 * 订单金额不能超过账户余额的指定百分比。
 */
@Component
public class MaxLossPerTradeRule implements RiskRule {

    private final BigDecimal maxLossPct;

    public MaxLossPerTradeRule(RiskProperties riskProperties) {
        this.maxLossPct = riskProperties.getMaxLossPerTradePct();
    }

    @Override
    public String name() {
        return "MaxLossPerTrade";
    }

    @Override
    public RiskCheckResult check(Order order, VirtualAccount account) {
        BigDecimal orderValue = order.quantity().multiply(order.price() != null ? order.price() : BigDecimal.ZERO);
        BigDecimal maxValue = account.getBalance().multiply(maxLossPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        if (orderValue.compareTo(maxValue) > 0) {
            return RiskCheckResult.rejected(
                    OrderRejectReason.RISK_REJECTED,
                    String.format("订单金额 $%s 超过单笔限制 $%s (%.1f%%)",
                            orderValue.toPlainString(), maxValue.toPlainString(), maxLossPct)
            );
        }
        return RiskCheckResult.passed();
    }
}
