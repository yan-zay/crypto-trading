package com.tj.crypto.risk;

import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.execution.model.Order;

/**
 * 风控规则接口。
 * 每个实现类负责一项风控检查。
 */
public interface RiskRule {

    /**
     * 规则名称。
     */
    String name();

    /**
     * 检查订单是否通过风控。
     *
     * @param order   待执行订单
     * @param account 当前账户状态
     * @return 检查结果
     */
    RiskCheckResult check(Order order, TradingAccount account);

    /** 风控通过且订单成交后的记账钩子；check 本身必须保持无副作用。 */
    default void onOrderFilled(Order order) {
    }

    /**
     * Create the rule instance used by an isolated execution session.
     * Stateless rules may be shared; stateful rules must override this method.
     */
    default RiskRule newSession() {
        return this;
    }
}
