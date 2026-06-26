package com.tj.crypto.execution.model;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 订单，不可变值对象。
 * 表示一个执行意图（区别于 Trade，Trade 是已成交记录）。
 *
 * @param orderId       订单 ID
 * @param instrument    交易工具
 * @param side          多/空方向
 * @param type          订单类型
 * @param quantity      数量
 * @param price         价格（LIMIT 单需要，MARKET 为 null）
 * @param status        订单状态
 * @param rejectReason  拒绝原因
 * @param createdAt     创建时间
 * @param filledAt      成交时间
 */
public record Order(
        String orderId,
        Instrument instrument,
        OrderSide side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal price,
        OrderStatus status,
        OrderRejectReason rejectReason,
        long createdAt,
        long filledAt
) {
    /**
     * 创建新的待执行订单。
     */
    public static Order create(Instrument instrument, OrderSide side, OrderType type,
                               BigDecimal quantity, BigDecimal price, long timestamp) {
        return new Order(
                UUID.randomUUID().toString().substring(0, 8),
                instrument, side, type, quantity, price,
                OrderStatus.PENDING, OrderRejectReason.NONE,
                timestamp, 0
        );
    }

    /**
     * 创建已拒绝的订单。
     */
    public static Order rejected(Instrument instrument, OrderSide side, OrderType type,
                                 BigDecimal quantity, OrderRejectReason reason, long timestamp) {
        return new Order(
                UUID.randomUUID().toString().substring(0, 8),
                instrument, side, type, quantity, null,
                OrderStatus.REJECTED, reason,
                timestamp, 0
        );
    }

    /**
     * 创建已成交的订单。
     */
    public Order filled(long filledAt) {
        return new Order(orderId, instrument, side, type, quantity, price,
                OrderStatus.FILLED, OrderRejectReason.NONE, createdAt, filledAt);
    }

    public boolean isPending() { return status == OrderStatus.PENDING; }
    public boolean isFilled() { return status == OrderStatus.FILLED; }
    public boolean isRejected() { return status == OrderStatus.REJECTED; }
}
