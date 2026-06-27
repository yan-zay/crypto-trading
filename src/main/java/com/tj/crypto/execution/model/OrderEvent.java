package com.tj.crypto.execution.model;

import java.math.BigDecimal;

/**
 * 订单事件，驱动状态机流转。
 *
 * @param orderId        订单 ID
 * @param eventType      事件类型
 * @param timestamp      事件时间戳
 * @param fillPrice      成交价格（FILLED / PARTIALLY_FILLED 时有值）
 * @param fillQuantity   成交数量（FILLED / PARTIALLY_FILLED 时有值）
 * @param rejectReason   拒绝原因（REJECTED 时有值）
 */
public record OrderEvent(
        String orderId,
        EventType eventType,
        long timestamp,
        BigDecimal fillPrice,
        BigDecimal fillQuantity,
        OrderRejectReason rejectReason
) {
    /**
     * 事件类型。
     */
    public enum EventType {
        SUBMITTED,
        ACKNOWLEDGED,
        FILLED,
        PARTIALLY_FILLED,
        CANCEL_REQUESTED,
        CANCELLED,
        REJECTED,
        EXPIRED
    }

    // ─── 便捷工厂方法 ────────────────────────────────────────────

    public static OrderEvent submitted(String orderId, long timestamp) {
        return new OrderEvent(orderId, EventType.SUBMITTED, timestamp, null, null, null);
    }

    public static OrderEvent acknowledged(String orderId, long timestamp) {
        return new OrderEvent(orderId, EventType.ACKNOWLEDGED, timestamp, null, null, null);
    }

    public static OrderEvent filled(String orderId, long timestamp,
                                    BigDecimal fillPrice, BigDecimal fillQuantity) {
        return new OrderEvent(orderId, EventType.FILLED, timestamp, fillPrice, fillQuantity, null);
    }

    public static OrderEvent partiallyFilled(String orderId, long timestamp,
                                             BigDecimal fillPrice, BigDecimal fillQuantity) {
        return new OrderEvent(orderId, EventType.PARTIALLY_FILLED, timestamp, fillPrice, fillQuantity, null);
    }

    public static OrderEvent cancelRequested(String orderId, long timestamp) {
        return new OrderEvent(orderId, EventType.CANCEL_REQUESTED, timestamp, null, null, null);
    }

    public static OrderEvent cancelled(String orderId, long timestamp) {
        return new OrderEvent(orderId, EventType.CANCELLED, timestamp, null, null, null);
    }

    public static OrderEvent rejected(String orderId, long timestamp, OrderRejectReason reason) {
        return new OrderEvent(orderId, EventType.REJECTED, timestamp, null, null, reason);
    }

    public static OrderEvent expired(String orderId, long timestamp) {
        return new OrderEvent(orderId, EventType.EXPIRED, timestamp, null, null, null);
    }
}
