package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;

import java.math.BigDecimal;

/**
 * 持仓记录，不可变值对象。
 *
 * @param instrument  交易工具
 * @param side        多/空方向
 * @param quantity    持仓数量
 * @param entryPrice  开仓价格
 * @param entryTime   开仓时间
 */
public record Position(
        Instrument instrument,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        long entryTime
) {
    /**
     * 计算未实现盈亏。
     *
     * @param currentPrice 当前价格
     * @return 盈亏金额（正=盈利，负=亏损）
     */
    public BigDecimal unrealizedPnL(BigDecimal currentPrice) {
        BigDecimal diff = currentPrice.subtract(entryPrice);
        if (side == OrderSide.SHORT) {
            diff = diff.negate();
        }
        return diff.multiply(quantity);
    }
}
