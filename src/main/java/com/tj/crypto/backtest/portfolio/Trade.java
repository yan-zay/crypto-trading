package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;

import java.math.BigDecimal;

/**
 * 交易记录，不可变值对象。
 *
 * @param instrument   交易工具
 * @param side         多/空方向
 * @param quantity     交易数量
 * @param entryPrice   开仓价格
 * @param exitPrice    平仓价格
 * @param entryTime    开仓时间
 * @param exitTime     平仓时间
 * @param realizedPnL  已实现盈亏
 */
public record Trade(
        Instrument instrument,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        long entryTime,
        long exitTime,
        BigDecimal realizedPnL
) {
    /**
     * 是否盈利。
     */
    public boolean isProfitable() {
        return realizedPnL.compareTo(BigDecimal.ZERO) > 0;
    }
}
