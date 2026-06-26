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
 * 最大持仓量限制。
 * 单个持仓金额不能超过账户余额的指定百分比。
 */
@Component
public class MaxPositionSizeRule implements RiskRule {

    private final BigDecimal maxSizePct;

    public MaxPositionSizeRule(RiskProperties riskProperties) {
        this.maxSizePct = riskProperties.getMaxSizePct();
    }

    @Override
    public String name() {
        return "MaxPositionSize";
    }

    @Override
    public RiskCheckResult check(Order order, VirtualAccount account) {
        BigDecimal orderValue = order.quantity().multiply(order.price() != null ? order.price() : BigDecimal.ZERO);
        BigDecimal maxValue = account.getBalance()
                .multiply(maxSizePct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        if (orderValue.compareTo(maxValue) > 0) {
            return RiskCheckResult.rejected(
                    OrderRejectReason.RISK_REJECTED,
                    String.format("持仓金额 $%s 超过限制 $%s (%.1f%%)",
                            orderValue.toPlainString(), maxValue.toPlainString(), maxSizePct)
            );
        }
        return RiskCheckResult.passed();
    }
}
