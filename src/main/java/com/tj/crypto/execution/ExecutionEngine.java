package com.tj.crypto.execution;

import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 执行引擎。
 * 信号 → 风控 → 仓位计算 → 滑点 → 下单。
 *
 * <p>使用 {@link OrderStateMachine} 管理订单生命周期。
 *
 * <p>流程：
 * <ol>
 *   <li>创建订单意图（CREATED）</li>
 *   <li>PositionSizer.calculateSize() 仓位计算</li>
 *   <li>RiskEngine.checkAll() 风控检查</li>
 *   <li>OrderStateMachine 驱动状态流转：CREATED → SUBMITTED → ACKNOWLEDGED → FILLED</li>
 *   <li>SlippageModel.applySlippage() 滑点模拟</li>
 *   <li>VirtualAccount.openPosition/closePosition 执行</li>
 * </ol>
 */
@Slf4j
@Component
public class ExecutionEngine {

    private final RiskEngine riskEngine;
    private final PositionSizer positionSizer;
    private final SlippageModel slippageModel;

    public ExecutionEngine(RiskEngine riskEngine, PositionSizer positionSizer, SlippageModel slippageModel) {
        this.riskEngine = riskEngine;
        this.positionSizer = positionSizer;
        this.slippageModel = slippageModel;
    }

    /**
     * 执行交易信号。
     *
     * @param signal        交易信号
     * @param account       虚拟账户
     * @param currentPrice  当前价格
     * @param timestamp     时间戳
     * @return 执行后的订单（可能被拒绝），HOLD 信号返回 null
     */
    public Order execute(SignalEvent signal, VirtualAccount account, BigDecimal currentPrice, long timestamp) {
        // 1. 信号类型转换为订单方向
        if (signal.type() == SignalType.HOLD) {
            return null; // HOLD 不产生订单
        }

        OrderSide side = signal.type() == SignalType.BUY ? OrderSide.LONG : OrderSide.SHORT;

        // 2. 计算仓位
        BigDecimal quantity = positionSizer.calculateSize(signal, account, currentPrice);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            Order rejected = Order.rejected(signal.instrument(), side, OrderType.MARKET, BigDecimal.ZERO,
                    OrderRejectReason.INSUFFICIENT_BALANCE, timestamp);
            logOrderEvent(rejected, OrderEvent.rejected(rejected.orderId(), timestamp,
                    OrderRejectReason.INSUFFICIENT_BALANCE));
            return rejected;
        }

        // 3. 创建订单意图（CREATED）
        Order order = Order.create(signal.instrument(), side, OrderType.MARKET,
                quantity, currentPrice, timestamp);
        logOrderEvent(order, OrderEvent.submitted(order.orderId(), timestamp));

        // 4. CREATED → SUBMITTED
        order = OrderStateMachine.transition(order,
                OrderEvent.submitted(order.orderId(), timestamp));

        // 5. 风控检查
        RiskCheckResult riskResult = riskEngine.checkAll(order, account);
        if (!riskResult.isPassed()) {
            Order rejected = OrderStateMachine.transition(order,
                    OrderEvent.rejected(order.orderId(), timestamp, riskResult.rejectReason()));
            logOrderEvent(rejected, OrderEvent.rejected(order.orderId(), timestamp,
                    riskResult.rejectReason()));
            return rejected;
        }

        // 6. SUBMITTED → ACKNOWLEDGED
        order = OrderStateMachine.transition(order,
                OrderEvent.acknowledged(order.orderId(), timestamp));

        // 7. 应用滑点
        BigDecimal executionPrice = slippageModel.applySlippage(currentPrice, side, OrderType.MARKET);

        // 8. 执行交易
        boolean success;
        if (side == OrderSide.LONG) {
            success = account.openPosition(signal.instrument(), side, quantity, executionPrice, timestamp);
        } else {
            success = account.closePosition(signal.instrument(), executionPrice, timestamp) != null;
        }

        if (!success) {
            Order rejected = OrderStateMachine.transition(order,
                    OrderEvent.rejected(order.orderId(), timestamp, OrderRejectReason.INSUFFICIENT_BALANCE));
            logOrderEvent(rejected, OrderEvent.rejected(order.orderId(), timestamp,
                    OrderRejectReason.INSUFFICIENT_BALANCE));
            return rejected;
        }

        // 9. ACKNOWLEDGED → FILLED
        Order filled = OrderStateMachine.transition(order,
                OrderEvent.filled(order.orderId(), timestamp, executionPrice, quantity));
        logOrderEvent(filled, OrderEvent.filled(order.orderId(), timestamp, executionPrice, quantity));

        log.info("[EXEC] {} {} {} @ ${} (slippage: {} → {})",
                side, quantity, signal.instrument().symbol(), executionPrice,
                currentPrice, executionPrice);

        return filled;
    }

    /**
     * 部分成交。
     *
     * @param order         当前订单（ACKNOWLEDGED 或 PARTIALLY_FILLED 状态）
     * @param fillPrice     成交价格
     * @param fillQuantity  成交数量
     * @param timestamp     时间戳
     * @return 更新后的订单
     * @throws IllegalStateException 如果订单状态不允许部分成交
     */
    public Order partialFill(Order order, BigDecimal fillPrice, BigDecimal fillQuantity, long timestamp) {
        OrderEvent event = OrderEvent.partiallyFilled(order.orderId(), timestamp, fillPrice, fillQuantity);
        Order updated = OrderStateMachine.transition(order, event);
        logOrderEvent(updated, event);
        return updated;
    }

    /**
     * 完全成交（从部分成交状态）。
     *
     * @param order         当前订单（ACKNOWLEDGED 或 PARTIALLY_FILLED 状态）
     * @param fillPrice     成交价格
     * @param fillQuantity  本次成交数量
     * @param timestamp     时间戳
     * @return 已完全成交的订单
     */
    public Order completeFill(Order order, BigDecimal fillPrice, BigDecimal fillQuantity, long timestamp) {
        OrderEvent event = OrderEvent.filled(order.orderId(), timestamp, fillPrice, fillQuantity);
        Order filled = OrderStateMachine.transition(order, event);
        logOrderEvent(filled, event);
        return filled;
    }

    /**
     * 请求撤单。
     *
     * @param order      当前订单
     * @param timestamp  时间戳
     * @return 撤单请求中的订单
     */
    public Order requestCancel(Order order, long timestamp) {
        OrderEvent event = OrderEvent.cancelRequested(order.orderId(), timestamp);
        Order updated = OrderStateMachine.transition(order, event);
        logOrderEvent(updated, event);
        return updated;
    }

    /**
     * 确认撤单。
     *
     * @param order      当前订单（CANCEL_REQUESTED 状态）
     * @param timestamp  时间戳
     * @return 已取消的订单
     */
    public Order confirmCancel(Order order, long timestamp) {
        OrderEvent event = OrderEvent.cancelled(order.orderId(), timestamp);
        Order cancelled = OrderStateMachine.transition(order, event);
        logOrderEvent(cancelled, event);
        return cancelled;
    }

    /**
     * 记录订单事件日志。
     */
    private void logOrderEvent(Order order, OrderEvent event) {
        log.debug("[ORDER_EVENT] orderId={} event={} status={} filled={}/{}",
                order.orderId(), event.eventType(), order.status(),
                order.filledQuantity(), order.quantity());
    }
}
