package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.OrderSide;

import java.math.BigDecimal;

/**
 * 手续费模型接口。
 * 计算交易手续费，用于回测中的成本模拟。
 */
public interface FeeModel {

    /**
     * 计算手续费。
     *
     * @param side     订单方向（LONG/SHORT）
     * @param quantity 交易数量
     * @param price    交易价格
     * @return 手续费金额（非负数）
     */
    BigDecimal calculateFee(OrderSide side, BigDecimal quantity, BigDecimal price);
}
