package com.tj.crypto.risk;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.execution.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 风控引擎。
 * 串联所有风控规则，任一规则不通过则拒绝订单。
 *
 * 设计决策：
 * - 规则按注册顺序执行，短路返回
 * - 规则异常视为拒绝（安全优先）
 */
@Slf4j
@Component
public class RiskEngine {

    private final List<RiskRule> rules;

    public RiskEngine(List<RiskRule> rules) {
        this.rules = rules;
        log.info("RiskEngine initialized with {} rules", rules.size());
    }

    /**
     * 执行所有风控检查。
     *
     * @param order   待执行订单
     * @param account 当前账户状态
     * @return 检查结果（全部通过返回 passed，任一不通过返回拒绝原因）
     */
    public RiskCheckResult checkAll(Order order, VirtualAccount account) {
        for (RiskRule rule : rules) {
            try {
                RiskCheckResult result = rule.check(order, account);
                if (!result.isPassed()) {
                    log.warn("Risk check failed [{}]: {}", rule.name(), result.message());
                    return result;
                }
            } catch (Exception e) {
                log.error("Risk rule {} threw exception: {}", rule.name(), e.getMessage(), e);
                return RiskCheckResult.rejected(
                        com.tj.crypto.execution.model.OrderRejectReason.RISK_REJECTED,
                        "风控规则异常: " + rule.name()
                );
            }
        }
        return RiskCheckResult.passed();
    }
}
