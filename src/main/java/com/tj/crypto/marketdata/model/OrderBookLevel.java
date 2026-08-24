package com.tj.crypto.marketdata.model;

import java.math.BigDecimal;

/** L2 订单簿价位；增量事件允许零数量表示删除该价位。 */
public record OrderBookLevel(BigDecimal price, BigDecimal quantity) {
    public OrderBookLevel {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        if (quantity == null || quantity.signum() < 0) {
            throw new IllegalArgumentException("quantity must not be negative");
        }
    }
}
