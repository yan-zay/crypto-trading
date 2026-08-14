package com.tj.crypto.execution;

import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.OrderType;

import java.math.BigDecimal;

/**
 * 滑点模型接口。
 * 模拟真实交易中的价格滑点。
 */
public interface SlippageModel {

    /**
     * 应用滑点。
     *
     * @param price    原始价格
     * @param side     订单方向
     * @param type     订单类型
     * @return 应用滑点后的价格
     */
    BigDecimal applySlippage(BigDecimal price, OrderSide side, OrderType type);

    /** Volume-aware quote; default preserves legacy fixed-slippage behavior. */
    default ExecutionPricing quote(BigDecimal price, OrderSide side, OrderType type,
                                   BigDecimal requestedQuantity, BigDecimal baseVolume) {
        return quote(price, side, type, requestedQuantity, baseVolume, true);
    }

    default ExecutionPricing quote(BigDecimal price, OrderSide side, OrderType type,
                                   BigDecimal requestedQuantity, BigDecimal baseVolume,
                                   boolean allowPartial) {
        return new ExecutionPricing(requestedQuantity, applySlippage(price, side, type),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                requestedQuantity);
    }
}
