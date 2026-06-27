package com.tj.crypto.execution.model;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 订单，不可变值对象。
 * 表示一个执行意图（区别于 Trade，Trade 是已成交记录）。
 *
 * <p>通过 {@link OrderStateMachine} 驱动状态流转，本类只提供便捷工厂方法。
 *
 * @param orderId         订单 ID
 * @param clientOrderId   客户端自定义订单 ID
 * @param instrument      交易工具
 * @param side            多/空方向
 * @param type            订单类型
 * @param quantity         委托数量
 * @param price           委托价格（LIMIT 单需要，MARKET 为 null）
 * @param filledQuantity  已成交数量
 * @param avgFillPrice    平均成交价格
 * @param status          订单状态
 * @param rejectReason    拒绝原因
 * @param createdAt       创建时间
 * @param submittedAt     提交时间
 * @param filledAt        完全成交时间
 * @param cancelledAt     取消时间
 */
public record Order(
        String orderId,
        String clientOrderId,
        Instrument instrument,
        OrderSide side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal filledQuantity,
        BigDecimal avgFillPrice,
        OrderStatus status,
        OrderRejectReason rejectReason,
        long createdAt,
        long submittedAt,
        long filledAt,
        long cancelledAt
) {
    /**
     * 创建新的待执行订单（CREATED 状态）。
     */
    public static Order create(Instrument instrument, OrderSide side, OrderType type,
                               BigDecimal quantity, BigDecimal price, long timestamp) {
        return new Order(
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString().substring(0, 8),
                instrument, side, type, quantity, price,
                BigDecimal.ZERO, null,
                OrderStatus.CREATED, OrderRejectReason.NONE,
                timestamp, 0, 0, 0
        );
    }

    /**
     * 创建已拒绝的订单。
     */
    public static Order rejected(Instrument instrument, OrderSide side, OrderType type,
                                 BigDecimal quantity, OrderRejectReason reason, long timestamp) {
        return new Order(
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString().substring(0, 8),
                instrument, side, type, quantity, null,
                BigDecimal.ZERO, null,
                OrderStatus.REJECTED, reason,
                timestamp, 0, 0, 0
        );
    }

    /**
     * @deprecated 通过 OrderStateMachine.transition() 驱动状态流转。
     */
    @Deprecated
    public Order filled(long filledAt) {
        return new Order(orderId, clientOrderId, instrument, side, type, quantity, price,
                quantity, price,
                OrderStatus.FILLED, OrderRejectReason.NONE,
                createdAt, submittedAt, filledAt, cancelledAt);
    }

    /** 是否为活跃状态 */
    public boolean isActive() {
        return status.isActive();
    }

    public boolean isPending() {
        return status == OrderStatus.CREATED || status == OrderStatus.PENDING;
    }

    public boolean isFilled() {
        return status == OrderStatus.FILLED;
    }

    public boolean isRejected() {
        return status == OrderStatus.REJECTED;
    }

    public boolean isCancelled() {
        return status == OrderStatus.CANCELLED;
    }
}
