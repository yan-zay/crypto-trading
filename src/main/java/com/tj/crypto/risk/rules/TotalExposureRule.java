package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.backtest.portfolio.AccountRiskSnapshot;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 全组合暴露限制。
 * 所有持仓的总价值不能超过账户总权益的指定百分比。
 */
@Slf4j
@Component
public class TotalExposureRule implements RiskRule {

    private static final BigDecimal DEFAULT_MAX_TOTAL_EXPOSURE_PCT = BigDecimal.valueOf(80);
    private static final int SCALE = 4;

    private final BigDecimal maxTotalExposurePct;

    @Autowired
    public TotalExposureRule() {
        this(DEFAULT_MAX_TOTAL_EXPOSURE_PCT);
    }

    public TotalExposureRule(BigDecimal maxTotalExposurePct) {
        this.maxTotalExposurePct = maxTotalExposurePct;
    }

    @Override
    public String name() {
        return "TotalExposure";
    }

    @Override
    public RiskCheckResult check(Order order, TradingAccount account) {
        if (order.reduceOnly()) {
            return RiskCheckResult.passed();
        }
        BigDecimal orderValue = order.quantity().multiply(
                order.price() != null ? order.price() : BigDecimal.ZERO);
        AccountRiskSnapshot snapshot = account.riskSnapshot(order.instrument(), order.price());
        BigDecimal totalAfterOrder = snapshot.grossExposure().add(orderValue);
        BigDecimal accountEquity = snapshot.equity();

        if (accountEquity.compareTo(BigDecimal.ZERO) <= 0) {
            return RiskCheckResult.passed();
        }

        BigDecimal exposurePct = totalAfterOrder
                .divide(accountEquity, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (exposurePct.compareTo(maxTotalExposurePct) > 0) {
            return RiskCheckResult.rejected(
                    OrderRejectReason.EXPOSURE_LIMIT,
                    String.format("总暴露 %.1f%% 超过限制 %.1f%%",
                            exposurePct, maxTotalExposurePct)
            );
        }

        return RiskCheckResult.passed();
    }

    public BigDecimal getMaxTotalExposurePct() {
        return maxTotalExposurePct;
    }
}
