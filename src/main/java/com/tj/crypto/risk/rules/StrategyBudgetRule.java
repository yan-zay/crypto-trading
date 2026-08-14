package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.tj.crypto.common.domain.InstrumentId;

/**
 * 策略级预算限制。
 * 限制单个策略的最大资金使用量，防止单策略过度集中。
 *
 * <p>逻辑：
 * <ol>
 *   <li>跟踪每个策略的累计资金使用量</li>
 *   <li>当策略使用资金超过账户余额的指定比例时，拒绝新订单</li>
 * </ol>
 */
@Slf4j
@Component
public class StrategyBudgetRule implements RiskRule {

    private static final BigDecimal DEFAULT_MAX_STRATEGY_BUDGET_PCT = BigDecimal.valueOf(50);
    private static final int SCALE = 4;

    private final BigDecimal maxStrategyBudgetPct;

    /** 策略资金使用跟踪：strategyName -> usedAmount */
    private final ConcurrentHashMap<String, BigDecimal> strategyUsage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InstrumentId, String> positionOwners = new ConcurrentHashMap<>();

    @Autowired
    public StrategyBudgetRule() {
        this(DEFAULT_MAX_STRATEGY_BUDGET_PCT);
    }

    public StrategyBudgetRule(BigDecimal maxStrategyBudgetPct) {
        this.maxStrategyBudgetPct = maxStrategyBudgetPct;
    }

    @Override
    public String name() {
        return "StrategyBudget";
    }

    @Override
    public RiskCheckResult check(Order order, TradingAccount account) {
        if (order.reduceOnly()) {
            return RiskCheckResult.passed();
        }
        String strategyKey = order.strategyId();

        BigDecimal orderValue = order.quantity().multiply(
                order.price() != null ? order.price() : BigDecimal.ZERO);

        BigDecimal currentUsage = strategyUsage.getOrDefault(strategyKey, BigDecimal.ZERO);
        BigDecimal newUsage = currentUsage.add(orderValue);

        BigDecimal maxBudget = account.getInitialBalance()
                .multiply(maxStrategyBudgetPct)
                .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);

        if (newUsage.compareTo(maxBudget) > 0) {
            return RiskCheckResult.rejected(
                    OrderRejectReason.EXPOSURE_LIMIT,
                    String.format("策略 %s 资金使用 %.2f 超过预算限制 %.2f (%.1f%%)",
                            strategyKey, newUsage, maxBudget, maxStrategyBudgetPct)
            );
        }

        return RiskCheckResult.passed();
    }

    @Override
    public void onOrderFilled(Order order) {
        BigDecimal notional = order.notional();
        if (order.reduceOnly()) {
            String owner = positionOwners.remove(order.instrument().id());
            if (owner != null) {
                strategyUsage.compute(owner, (ignored, current) -> {
                    BigDecimal remaining = (current == null ? BigDecimal.ZERO : current).subtract(notional);
                    return remaining.signum() > 0 ? remaining : null;
                });
            }
            return;
        }

        positionOwners.put(order.instrument().id(), order.strategyId());
        strategyUsage.merge(order.strategyId(), notional, BigDecimal::add);
    }

    /**
     * 重置策略使用量（平仓后调用）。
     */
    public void resetUsage(String strategyKey) {
        strategyUsage.remove(strategyKey);
    }

    /**
     * 获取策略当前使用量。
     */
    public BigDecimal getUsage(String strategyKey) {
        return strategyUsage.getOrDefault(strategyKey, BigDecimal.ZERO);
    }

    public BigDecimal getMaxStrategyBudgetPct() {
        return maxStrategyBudgetPct;
    }

    @Override
    public StrategyBudgetRule newSession() {
        return new StrategyBudgetRule(maxStrategyBudgetPct);
    }
}
