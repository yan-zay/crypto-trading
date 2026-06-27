package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单币种暴露限制。
 * 单个币种的持仓价值不能超过账户总权益的指定百分比。
 */
@Slf4j
@Component
public class PerSymbolExposureRule implements RiskRule {

    private static final BigDecimal DEFAULT_MAX_EXPOSURE_PCT = BigDecimal.valueOf(40);
    private static final int SCALE = 4;

    private final BigDecimal maxExposurePct;

    public PerSymbolExposureRule() {
        this(DEFAULT_MAX_EXPOSURE_PCT);
    }

    public PerSymbolExposureRule(BigDecimal maxExposurePct) {
        this.maxExposurePct = maxExposurePct;
    }

    @Override
    public String name() {
        return "PerSymbolExposure";
    }

    @Override
    public RiskCheckResult check(Order order, VirtualAccount account) {
        BigDecimal orderValue = order.quantity().multiply(
                order.price() != null ? order.price() : BigDecimal.ZERO);

        // 当前该币种的持仓价值
        String symbol = order.instrument().symbol();
        BigDecimal existingValue = account.getPositions().entrySet().stream()
                .filter(e -> e.getKey().equals(symbol))
                .map(e -> e.getValue().quantity().multiply(e.getValue().entryPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalValue = existingValue.add(orderValue);
        BigDecimal accountEquity = account.getBalance().add(existingValue);

        if (accountEquity.compareTo(BigDecimal.ZERO) <= 0) {
            return RiskCheckResult.passed();
        }

        BigDecimal exposurePct = totalValue
                .divide(accountEquity, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (exposurePct.compareTo(maxExposurePct) > 0) {
            return RiskCheckResult.rejected(
                    OrderRejectReason.EXPOSURE_LIMIT,
                    String.format("%s 暴露 %.1f%% 超过限制 %.1f%%",
                            symbol, exposurePct, maxExposurePct)
            );
        }

        return RiskCheckResult.passed();
    }

    public BigDecimal getMaxExposurePct() {
        return maxExposurePct;
    }
}
