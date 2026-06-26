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
}
