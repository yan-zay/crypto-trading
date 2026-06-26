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
 * @param totalFee     总手续费（开仓 + 平仓）
 */
public record Trade(
        Instrument instrument,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        long entryTime,
        long exitTime,
        BigDecimal realizedPnL,
        BigDecimal totalFee
) {
    /**
     * 紧凑构造函数，确保 totalFee 不为 null。
     */
    public Trade {
        if (totalFee == null) {
            totalFee = BigDecimal.ZERO;
        }
    }

    /**
     * 便捷构造函数（无手续费，默认为 0）。
     */
    public Trade(Instrument instrument, OrderSide side, BigDecimal quantity,
                 BigDecimal entryPrice, BigDecimal exitPrice,
                 long entryTime, long exitTime, BigDecimal realizedPnL) {
        this(instrument, side, quantity, entryPrice, exitPrice, entryTime, exitTime, realizedPnL, BigDecimal.ZERO);
    }

    /**
     * 是否盈利。
     */
    public boolean isProfitable() {
        return realizedPnL.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 净盈亏 = 已实现盈亏 - 总手续费。
     */
    public BigDecimal netPnL() {
        return realizedPnL.subtract(totalFee);
    }
}
