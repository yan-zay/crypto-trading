package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 永续合约持仓记录，不可变值对象。
 *
 * @param instrument       交易工具
 * @param side             多/空方向
 * @param quantity         持仓数量（合约张数或标的数量）
 * @param entryPrice       开仓均价
 * @param leverage         杠杆倍数
 * @param marginMode       保证金模式
 * @param margin           保证金（开仓时锁定）
 * @param unrealizedPnL    未实现盈亏（快照值，需通过 calculateUnrealizedPnL 实时计算）
 * @param liquidationPrice 强平价格
 */
public record FuturesPosition(
        Instrument instrument,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        int leverage,
        MarginMode marginMode,
        BigDecimal margin,
        BigDecimal unrealizedPnL,
        BigDecimal liquidationPrice
) {

    private static final int SCALE = 8;
    private static final BigDecimal MAINTENANCE_MARGIN_RATE = new BigDecimal("0.005");

    /**
     * 计算未实现盈亏。
     * 多仓: (currentPrice - entryPrice) * quantity
     * 空仓: (entryPrice - currentPrice) * quantity
     *
     * @param currentPrice 当前价格
     * @return 盈亏金额（正=盈利，负=亏损）
     */
    public BigDecimal calculateUnrealizedPnL(BigDecimal currentPrice) {
        BigDecimal diff = currentPrice.subtract(entryPrice);
        if (side == OrderSide.SHORT) {
            diff = diff.negate();
        }
        return diff.multiply(quantity).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算强平价格。
     *
     * 逐仓模式下，当 margin + unrealizedPnL <= maintenanceMargin 时触发强平。
     * maintenanceMargin = quantity * entryPrice * MAINTENANCE_MARGIN_RATE
     *
     * 多仓: liqPrice = entryPrice - (margin - maintenanceMargin) / quantity
     * 空仓: liqPrice = entryPrice + (margin - maintenanceMargin) / quantity
     *
     * @return 强平价格
     */
    public BigDecimal calculateLiquidationPrice() {
        BigDecimal positionValue = quantity.multiply(entryPrice);
        BigDecimal maintenanceMargin = positionValue.multiply(MAINTENANCE_MARGIN_RATE)
                .setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal marginBuffer = margin.subtract(maintenanceMargin);
        BigDecimal priceBuffer = marginBuffer.divide(quantity, SCALE, RoundingMode.HALF_UP);

        if (side == OrderSide.LONG) {
            return entryPrice.subtract(priceBuffer);
        } else {
            return entryPrice.add(priceBuffer);
        }
    }

    /**
     * 是否已触发强平（当前价格越过强平价）。
     *
     * @param currentPrice 当前价格
     * @return true 如果应被强平
     */
    public boolean shouldLiquidate(BigDecimal currentPrice) {
        if (side == OrderSide.LONG) {
            return currentPrice.compareTo(liquidationPrice) <= 0;
        } else {
            return currentPrice.compareTo(liquidationPrice) >= 0;
        }
    }
}
