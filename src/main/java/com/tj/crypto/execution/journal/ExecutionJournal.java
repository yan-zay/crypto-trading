package com.tj.crypto.execution.journal;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;

/**
 * OMS 执行日志端口。
 *
 * <p>执行引擎只发布已经发生的订单状态变化，持久化、消息投递或审计实现位于端口之外。
 * Trade 仅在平仓成交时存在，并与产生该成交的订单一起写入。
 */
public interface ExecutionJournal {

    void record(Order order, OrderEvent event, Trade trade);

    default void record(Order order, OrderEvent event) {
        record(order, event, null);
    }

    /**
     * Required journals make execution fail closed when the write cannot be completed.
     * Observability-only journals should keep the default best-effort behavior.
     */
    default boolean requiredForExecution() {
        return false;
    }
}
