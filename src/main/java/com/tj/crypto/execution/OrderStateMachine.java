package com.tj.crypto.execution;

import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderEvent;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.execution.model.OrderStatus;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机。
 *
 * <p>所有 Order 对象都是不可变的——transition 返回新实例，不修改原对象。
 *
 * <p>合法状态转换：
 * <pre>
 *   CREATED      → SUBMITTED, REJECTED
 *   SUBMITTED    → ACKNOWLEDGED, REJECTED
 *   ACKNOWLEDGED → PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, REJECTED, EXPIRED
 *   PARTIALLY_FILLED → PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, REJECTED, EXPIRED
 *   CANCEL_REQUESTED → CANCELLED, FILLED (撤单请求期间可能已成交)
 * </pre>
 *
 * <p>终态（FILLED, CANCELLED, REJECTED, EXPIRED）不可再转换。
 */
@Slf4j
public final class OrderStateMachine {

    /**
     * 合法的状态转换映射：当前状态 → 允许的目标状态集合。
     */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(OrderStatus.CREATED, Set.of(
                    OrderStatus.SUBMITTED,
                    OrderStatus.REJECTED
            )),
            Map.entry(OrderStatus.PENDING, Set.of(
                    OrderStatus.SUBMITTED,
                    OrderStatus.REJECTED
            )),
            Map.entry(OrderStatus.SUBMITTED, Set.of(
                    OrderStatus.ACKNOWLEDGED,
                    OrderStatus.REJECTED
            )),
            Map.entry(OrderStatus.ACKNOWLEDGED, Set.of(
                    OrderStatus.PARTIALLY_FILLED,
                    OrderStatus.FILLED,
                    OrderStatus.CANCEL_REQUESTED,
                    OrderStatus.REJECTED,
                    OrderStatus.EXPIRED
            )),
            Map.entry(OrderStatus.PARTIALLY_FILLED, Set.of(
                    OrderStatus.PARTIALLY_FILLED,
                    OrderStatus.FILLED,
                    OrderStatus.CANCEL_REQUESTED,
                    OrderStatus.REJECTED,
                    OrderStatus.EXPIRED
            )),
            Map.entry(OrderStatus.CANCEL_REQUESTED, Set.of(
                    OrderStatus.PARTIALLY_FILLED,
                    OrderStatus.CANCELLED,
                    OrderStatus.FILLED
            )),
            Map.entry(OrderStatus.UNKNOWN, Set.of(
                    OrderStatus.ACKNOWLEDGED,
                    OrderStatus.CANCEL_REQUESTED,
                    OrderStatus.REJECTED,
                    OrderStatus.EXPIRED
            ))
    );

    private OrderStateMachine() {
        // 工具类，禁止实例化
    }

    /**
     * 执行状态转换。
     *
     * @param order  当前订单（不可变，不会被修改）
     * @param event  触发转换的事件
     * @return 转换后的新 Order 实例
     * @throws IllegalStateException 如果转换不合法
     */
    public static Order transition(Order order, OrderEvent event) {
        if (!order.orderId().equals(event.orderId())) {
            throw new IllegalArgumentException("Order event belongs to a different order");
        }
        OrderStatus currentStatus = order.status();
        OrderStatus targetStatus = resolveTargetStatus(currentStatus, event);

        validateTransition(currentStatus, targetStatus);
        validateFill(order, event);

        return switch (event.eventType()) {
            case CREATED -> throw new IllegalArgumentException(
                    "CREATED 是订单初始审计事件，不触发状态转换");
            case SUBMITTED -> new Order(
                    order.orderId(), order.clientOrderId(),
                    order.instrument(), order.side(), order.type(),
                    order.quantity(), order.price(),
                    order.filledQuantity(), order.avgFillPrice(),
                    OrderStatus.SUBMITTED, OrderRejectReason.NONE,
                    order.createdAt(), event.timestamp(), order.filledAt(), order.cancelledAt(),
                    order.strategyId(), order.tradeSide(), order.positionSide(), order.reduceOnly()
            );

            case ACKNOWLEDGED -> new Order(
                    order.orderId(), order.clientOrderId(),
                    order.instrument(), order.side(), order.type(),
                    order.quantity(), order.price(),
                    order.filledQuantity(), order.avgFillPrice(),
                    OrderStatus.ACKNOWLEDGED, OrderRejectReason.NONE,
                    order.createdAt(), order.submittedAt(), order.filledAt(), order.cancelledAt(),
                    order.strategyId(), order.tradeSide(), order.positionSide(), order.reduceOnly()
            );

            case PARTIALLY_FILLED -> {
                BigDecimal newFilled = order.filledQuantity().add(event.fillQuantity());
                BigDecimal newAvgPrice = calculateAvgFillPrice(
                        order.filledQuantity(), order.avgFillPrice(),
                        event.fillQuantity(), event.fillPrice()
                );
                yield new Order(
                        order.orderId(), order.clientOrderId(),
                        order.instrument(), order.side(), order.type(),
                        order.quantity(), order.price(),
                        newFilled, newAvgPrice,
                        OrderStatus.PARTIALLY_FILLED, OrderRejectReason.NONE,
                        order.createdAt(), order.submittedAt(), order.filledAt(), order.cancelledAt(),
                        order.strategyId(), order.tradeSide(), order.positionSide(), order.reduceOnly()
                );
            }

            case FILLED -> {
                BigDecimal newFilled = order.filledQuantity().add(event.fillQuantity());
                BigDecimal newAvgPrice = calculateAvgFillPrice(
                        order.filledQuantity(), order.avgFillPrice(),
                        event.fillQuantity(), event.fillPrice()
                );
                yield new Order(
                        order.orderId(), order.clientOrderId(),
                        order.instrument(), order.side(), order.type(),
                        order.quantity(), order.price(),
                        newFilled, newAvgPrice,
                        OrderStatus.FILLED, OrderRejectReason.NONE,
                        order.createdAt(), order.submittedAt(), event.timestamp(), order.cancelledAt(),
                        order.strategyId(), order.tradeSide(), order.positionSide(), order.reduceOnly()
                );
            }

            case CANCEL_REQUESTED -> new Order(
                    order.orderId(), order.clientOrderId(),
                    order.instrument(), order.side(), order.type(),
                    order.quantity(), order.price(),
                    order.filledQuantity(), order.avgFillPrice(),
                    OrderStatus.CANCEL_REQUESTED, OrderRejectReason.NONE,
                    order.createdAt(), order.submittedAt(), order.filledAt(), order.cancelledAt(),
                    order.strategyId(), order.tradeSide(), order.positionSide(), order.reduceOnly()
            );

            case CANCELLED -> new Order(
                    order.orderId(), order.clientOrderId(),
                    order.instrument(), order.side(), order.type(),
                    order.quantity(), order.price(),
                    order.filledQuantity(), order.avgFillPrice(),
                    OrderStatus.CANCELLED, OrderRejectReason.NONE,
                    order.createdAt(), order.submittedAt(), order.filledAt(), event.timestamp(),
                    order.strategyId(), order.tradeSide(), order.positionSide(), order.reduceOnly()
            );

            case REJECTED -> new Order(
                    order.orderId(), order.clientOrderId(),
                    order.instrument(), order.side(), order.type(),
                    order.quantity(), order.price(),
                    order.filledQuantity(), order.avgFillPrice(),
                    OrderStatus.REJECTED, event.rejectReason() != null
                            ? event.rejectReason() : OrderRejectReason.NONE,
                    order.createdAt(), order.submittedAt(), order.filledAt(), order.cancelledAt(),
                    order.strategyId(), order.tradeSide(), order.positionSide(), order.reduceOnly()
            );

            case EXPIRED -> new Order(
                    order.orderId(), order.clientOrderId(),
                    order.instrument(), order.side(), order.type(),
                    order.quantity(), order.price(),
                    order.filledQuantity(), order.avgFillPrice(),
                    OrderStatus.EXPIRED, OrderRejectReason.NONE,
                    order.createdAt(), order.submittedAt(), order.filledAt(), order.cancelledAt(),
                    order.strategyId(), order.tradeSide(), order.positionSide(), order.reduceOnly()
            );
        };
    }

    private static void validateFill(Order order, OrderEvent event) {
        if (event.eventType() != OrderEvent.EventType.FILLED
                && event.eventType() != OrderEvent.EventType.PARTIALLY_FILLED) return;
        if (event.fillPrice() == null || event.fillPrice().signum() <= 0
                || event.fillQuantity() == null || event.fillQuantity().signum() <= 0) {
            throw new IllegalArgumentException("Fill price and quantity must be positive");
        }
        BigDecimal remaining = order.quantity().subtract(order.filledQuantity());
        if (event.fillQuantity().compareTo(remaining) > 0) {
            throw new IllegalArgumentException("Fill quantity exceeds order remainder");
        }
        if (event.eventType() == OrderEvent.EventType.PARTIALLY_FILLED
                && event.fillQuantity().compareTo(remaining) >= 0) {
            throw new IllegalArgumentException("Partial fill must leave a positive remainder");
        }
        if (event.eventType() == OrderEvent.EventType.FILLED
                && event.fillQuantity().compareTo(remaining) != 0) {
            throw new IllegalArgumentException("Final fill must consume the full remainder");
        }
    }

    /**
     * 验证状态转换是否合法。
     *
     * @throws IllegalStateException 转换不合法时
     */
    static void validateTransition(OrderStatus from, OrderStatus to) {
        Set<OrderStatus> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException(
                    String.format("非法订单状态转换: %s → %s", from, to)
            );
        }
    }

    /**
     * 根据事件类型推断目标状态。
     */
    private static OrderStatus resolveTargetStatus(OrderStatus current, OrderEvent event) {
        return switch (event.eventType()) {
            case CREATED -> throw new IllegalArgumentException(
                    "CREATED 是订单初始审计事件，不触发状态转换");
            case SUBMITTED -> OrderStatus.SUBMITTED;
            case ACKNOWLEDGED -> OrderStatus.ACKNOWLEDGED;
            case PARTIALLY_FILLED -> OrderStatus.PARTIALLY_FILLED;
            case FILLED -> OrderStatus.FILLED;
            case CANCEL_REQUESTED -> OrderStatus.CANCEL_REQUESTED;
            case CANCELLED -> OrderStatus.CANCELLED;
            case REJECTED -> OrderStatus.REJECTED;
            case EXPIRED -> OrderStatus.EXPIRED;
        };
    }

    /**
     * 计算加权平均成交价格。
     *
     * @param existingFilled  已成交数量
     * @param existingAvg     已成交均价
     * @param newFillQty      本次成交数量
     * @param newFillPrice    本次成交价格
     * @return 加权平均价格；首次成交时直接返回 newFillPrice
     */
    static BigDecimal calculateAvgFillPrice(BigDecimal existingFilled, BigDecimal existingAvg,
                                            BigDecimal newFillQty, BigDecimal newFillPrice) {
        if (existingFilled == null || existingFilled.compareTo(BigDecimal.ZERO) == 0) {
            return newFillPrice;
        }
        BigDecimal totalCost = existingAvg.multiply(existingFilled)
                .add(newFillPrice.multiply(newFillQty));
        BigDecimal totalQty = existingFilled.add(newFillQty);
        return totalCost.divide(totalQty, newFillPrice.scale(), java.math.RoundingMode.HALF_UP);
    }
}
