package com.tj.crypto.execution;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.execution.journal.ExecutionJournal;
import com.tj.crypto.execution.journal.ExecutionJournalException;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskEngine;
import com.tj.crypto.risk.PositionSizer;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

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
    private final KillSwitch killSwitch;
    private final List<ExecutionJournal> executionJournals;

    public ExecutionEngine(RiskEngine riskEngine, PositionSizer positionSizer,
                           SlippageModel slippageModel, KillSwitch killSwitch) {
        this(riskEngine, positionSizer, slippageModel, killSwitch, List.of());
    }

    @Autowired
    public ExecutionEngine(RiskEngine riskEngine, PositionSizer positionSizer,
                           SlippageModel slippageModel, KillSwitch killSwitch,
                           List<ExecutionJournal> executionJournals) {
        this.riskEngine = riskEngine;
        this.positionSizer = positionSizer;
        this.slippageModel = slippageModel;
        this.killSwitch = killSwitch;
        this.executionJournals = List.copyOf(executionJournals);
    }

    /**
     * 创建共享执行规则但不写 OMS 的执行器，用于回测。
     * 回测订单必须写入 backtest_run，而不能污染实时/模拟 OMS 订单表。
     */
    public ExecutionEngine withoutJournals() {
        return new ExecutionEngine(riskEngine.newSession(), positionSizer, slippageModel,
                new KillSwitch(), List.of());
    }

    /**
     * Creates an account-scoped execution session while retaining OMS journals and
     * the global kill switch. Used by paper accounts so a restart resets local risk state.
     */
    public ExecutionEngine newAccountSession() {
        return new ExecutionEngine(riskEngine.newSession(), positionSizer, slippageModel,
                killSwitch, executionJournals);
    }

    /**
     * 执行交易信号（使用默认交易所规则）。
     */
    public Order execute(SignalEvent signal, TradingAccount account, BigDecimal currentPrice, long timestamp) {
        return execute(signal, account, currentPrice, timestamp, ExchangeRules.DEFAULT, null);
    }

    /** Backtest overload with next-bar volume for capacity and market-impact modelling. */
    public Order execute(SignalEvent signal, TradingAccount account, BigDecimal currentPrice,
                         long timestamp, BigDecimal baseVolume) {
        return execute(signal, account, currentPrice, timestamp, ExchangeRules.DEFAULT, baseVolume);
    }

    /**
     * 执行交易信号。
     *
     * @param signal        交易信号
     * @param account       交易账户（VirtualAccount 或 FuturesAccount）
     * @param currentPrice  当前价格
     * @param timestamp     时间戳
     * @param exchangeRules 交易所规则（价格/数量精度、最小交易量）
     * @return 执行后的订单（可能被拒绝），HOLD 信号返回 null
     */
    public Order execute(SignalEvent signal, TradingAccount account, BigDecimal currentPrice,
                         long timestamp, ExchangeRules exchangeRules) {
        return execute(signal, account, currentPrice, timestamp, exchangeRules, null);
    }

    private Order execute(SignalEvent signal, TradingAccount account, BigDecimal currentPrice,
                          long timestamp, ExchangeRules exchangeRules, BigDecimal baseVolume) {
        // 1. 信号类型转换为订单方向
        if (signal.type() == SignalType.HOLD) {
            return null; // HOLD 不产生订单
        }

        OrderSide side = signal.type() == SignalType.BUY ? OrderSide.LONG : OrderSide.SHORT;
        TradeSide tradeSide = signal.type() == SignalType.BUY ? TradeSide.BUY : TradeSide.SELL;
        OrderSide currentPosSide = account.getPositionSide(signal.instrument());
        ExecutionAction action = resolveAction(side, currentPosSide);
        OrderSide positionSide = action == ExecutionAction.CLOSE ? currentPosSide : side;
        boolean reduceOnly = action == ExecutionAction.CLOSE;

        // 现货账户不能凭空开空；SELL 仅能减少已有现货多仓。
        if (signal.instrument().marketType() == MarketType.SPOT
                && side == OrderSide.SHORT && currentPosSide == null) {
            Order rejected = rejectedOrder(signal, tradeSide, side, OrderSide.LONG,
                    true, BigDecimal.ZERO, OrderRejectReason.NO_POSITION, timestamp);
            logOrderEvent(rejected, OrderEvent.rejected(
                    rejected.orderId(), timestamp, OrderRejectReason.NO_POSITION));
            return rejected;
        }

        // 1.1 KillSwitch 检查
        if (killSwitch.isActive()) {
            if (killSwitch.getMode() == KillSwitch.Mode.HALT) {
                Order rejected = rejectedOrder(signal, tradeSide, side, positionSide, reduceOnly,
                        BigDecimal.ZERO, OrderRejectReason.KILL_SWITCH, timestamp);
                log.warn("[KILL_SWITCH] HALT mode — rejecting order for {}",
                        signal.instrument().symbol());
                logOrderEvent(rejected, OrderEvent.rejected(rejected.orderId(), timestamp,
                        OrderRejectReason.KILL_SWITCH));
                return rejected;
            }
            if (killSwitch.getMode() == KillSwitch.Mode.CLOSE_ONLY) {
                // CLOSE_ONLY：只允许反向信号平掉已有仓位，拒绝开仓和加仓。
                if (action != ExecutionAction.CLOSE) {
                    Order rejected = rejectedOrder(signal, tradeSide, side, positionSide, reduceOnly,
                            BigDecimal.ZERO, OrderRejectReason.CLOSE_ONLY, timestamp);
                    log.warn("[KILL_SWITCH] CLOSE_ONLY mode — rejecting open order for {}",
                            signal.instrument().symbol());
                    logOrderEvent(rejected, OrderEvent.rejected(rejected.orderId(), timestamp,
                            OrderRejectReason.CLOSE_ONLY));
                    return rejected;
                }
            }
        }

        if (action == ExecutionAction.REJECT_SAME_SIDE) {
            Order rejected = rejectedOrder(signal, tradeSide, side, positionSide, reduceOnly,
                    BigDecimal.ZERO, OrderRejectReason.POSITION_EXISTS, timestamp);
            log.warn("[EXEC] Same-side signal rejected: {} already has {} position",
                    signal.instrument().symbol(), currentPosSide);
            logOrderEvent(rejected, OrderEvent.rejected(rejected.orderId(), timestamp,
                    OrderRejectReason.POSITION_EXISTS));
            return rejected;
        }

        // 2. 计算仓位
        BigDecimal quantity = action == ExecutionAction.CLOSE
                ? account.getPositionQuantity(signal.instrument())
                : positionSizer.calculateSize(signal, account, currentPrice);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            Order rejected = rejectedOrder(signal, tradeSide, side, positionSide, reduceOnly, BigDecimal.ZERO,
                    action == ExecutionAction.CLOSE ? OrderRejectReason.NO_POSITION : OrderRejectReason.INSUFFICIENT_BALANCE,
                    timestamp);
            logOrderEvent(rejected, OrderEvent.rejected(rejected.orderId(), timestamp,
                    rejected.rejectReason()));
            return rejected;
        }

        // 2.1 对齐到交易所规则
        quantity = exchangeRules.alignQuantity(quantity);
        BigDecimal executionPrice = exchangeRules.alignPrice(currentPrice);
        ExecutionPricing pricing = slippageModel.quote(executionPrice, side, OrderType.MARKET,
                quantity, baseVolume, action != ExecutionAction.CLOSE);
        quantity = exchangeRules.alignQuantity(pricing.filledQuantity());
        BigDecimal filledPrice = exchangeRules.alignPrice(pricing.fillPrice());

        // 2.2 验证订单满足交易所规则
        String validationError = exchangeRules.validate(executionPrice, quantity);
        if (validationError != null) {
            Order rejected = rejectedOrder(signal, tradeSide, side, positionSide, reduceOnly,
                    quantity, OrderRejectReason.INVALID_ORDER, timestamp);
            log.warn("[EXCHANGE_RULES] Order rejected: {} — {}", signal.instrument().symbol(), validationError);
            logOrderEvent(rejected, OrderEvent.rejected(rejected.orderId(), timestamp,
                    OrderRejectReason.INVALID_ORDER));
            return rejected;
        }

        // 3. 创建订单意图（CREATED）
        Order order = Order.create(signal.strategyName(), signal.instrument(), tradeSide,
                side, positionSide, reduceOnly, OrderType.MARKET,
                quantity, executionPrice, timestamp);
        logOrderEvent(order, OrderEvent.created(order.orderId(), timestamp));

        // 4. CREATED → SUBMITTED
        OrderEvent submittedEvent = OrderEvent.submitted(order.orderId(), timestamp);
        order = OrderStateMachine.transition(order, submittedEvent);
        logOrderEvent(order, submittedEvent);

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
        OrderEvent acknowledgedEvent = OrderEvent.acknowledged(order.orderId(), timestamp);
        order = OrderStateMachine.transition(order, acknowledgedEvent);
        logOrderEvent(order, acknowledgedEvent);

        // 7. 应用滑点
        // 8. 执行交易（位置感知：反向信号平仓，无仓信号开仓）
        boolean success;
        Trade closedTrade = null;
        if (action == ExecutionAction.CLOSE) {
            closedTrade = account.closePosition(signal.instrument(), filledPrice, timestamp);
            success = closedTrade != null;
        } else {
            success = account.openPosition(signal.instrument(), side, quantity, filledPrice, timestamp);
        }

        if (!success) {
            Order rejected = OrderStateMachine.transition(order,
                    OrderEvent.rejected(order.orderId(), timestamp, OrderRejectReason.INSUFFICIENT_BALANCE));
            logOrderEvent(rejected, OrderEvent.rejected(order.orderId(), timestamp,
                    OrderRejectReason.INSUFFICIENT_BALANCE));
            return rejected;
        }

        // 9. ACKNOWLEDGED → FILLED
        OrderEvent filledEvent = OrderEvent.filled(order.orderId(), timestamp, filledPrice, quantity);
        Order filled = OrderStateMachine.transition(order, filledEvent);
        riskEngine.onOrderFilled(filled);
        logOrderEvent(filled, filledEvent, closedTrade);

        log.info("[EXEC] {} {} {} @ ${} (slippage: {} → {})",
                side, quantity, signal.instrument().symbol(), filledPrice,
                currentPrice, filledPrice);

        return filled;
    }

    private ExecutionAction resolveAction(OrderSide signalSide, OrderSide currentPositionSide) {
        if (currentPositionSide == null) {
            return ExecutionAction.OPEN;
        }
        if (currentPositionSide != signalSide) {
            return ExecutionAction.CLOSE;
        }
        return ExecutionAction.REJECT_SAME_SIDE;
    }

    private Order rejectedOrder(SignalEvent signal, TradeSide tradeSide, OrderSide requestedSide,
                                OrderSide positionSide, boolean reduceOnly, BigDecimal quantity,
                                OrderRejectReason reason, long timestamp) {
        return Order.rejected(signal.strategyName(), signal.instrument(), tradeSide,
                requestedSide, positionSide, reduceOnly, OrderType.MARKET,
                quantity, reason, timestamp);
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
        logOrderEvent(order, event, null);
    }

    private void logOrderEvent(Order order, OrderEvent event, Trade trade) {
        log.debug("[ORDER_EVENT] orderId={} event={} status={} filled={}/{}",
                order.orderId(), event.eventType(), order.status(),
                order.filledQuantity(), order.quantity());
        for (ExecutionJournal journal : executionJournals) {
            try {
                journal.record(order, event, trade);
            } catch (RuntimeException e) {
                log.error("Failed to journal order event: orderId={}, event={}",
                        order.orderId(), event.eventType(), e);
                if (journal.requiredForExecution()) {
                    throw new ExecutionJournalException(
                            "Required OMS journal failed for order " + order.orderId()
                                    + " event " + event.eventType(), e);
                }
            }
        }
    }

    private enum ExecutionAction {
        OPEN,
        CLOSE,
        REJECT_SAME_SIDE
    }
}
