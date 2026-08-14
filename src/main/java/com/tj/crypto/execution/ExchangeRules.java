package com.tj.crypto.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 交易所规则模型。
 * 定义交易对的精度和最小交易要求。
 *
 * <p>典型值（Binance BTCUSDT 永续合约）：
 * <ul>
 *   <li>tickSize = 0.1（价格最小变动）</li>
 *   <li>stepSize = 0.001（数量最小变动）</li>
 *   <li>minNotional = 5（最小名义价值 5 USDT）</li>
 *   <li>minQuantity = 0.001（最小下单数量）</li>
 * </ul>
 *
 * @param tickSize     价格最小变动单位
 * @param stepSize     数量最小变动单位
 * @param minNotional  最小名义价值（quantity * price）
 * @param minQuantity  最小下单数量
 * @param maxQuantity  最大下单数量（0 表示无限制）
 * @param priceScale   价格小数位数
 * @param quantityScale 数量小数位数
 */
public record ExchangeRules(
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal minNotional,
        BigDecimal minQuantity,
        BigDecimal maxQuantity,
        int priceScale,
        int quantityScale
) {

    /** 默认规则（宽松，适用于回测和测试） */
    public static final ExchangeRules DEFAULT = new ExchangeRules(
            new BigDecimal("0.01"),   // tickSize
            new BigDecimal("0.00001"), // stepSize
            BigDecimal.ONE,           // minNotional
            BigDecimal.ZERO,          // minQuantity
            BigDecimal.ZERO,          // maxQuantity
            2,                        // priceScale
            6                         // quantityScale
    );

    /** Binance BTCUSDT 永续合约规则 */
    public static final ExchangeRules BINANCE_BTCUSDT = new ExchangeRules(
            new BigDecimal("0.1"),    // tickSize
            new BigDecimal("0.001"),  // stepSize
            new BigDecimal("5"),      // minNotional
            new BigDecimal("0.001"),  // minQuantity
            BigDecimal.ZERO,          // maxQuantity
            1,                        // priceScale
            3                         // quantityScale
    );

    /** Binance ETHUSDT 永续合约规则 */
    public static final ExchangeRules BINANCE_ETHUSDT = new ExchangeRules(
            new BigDecimal("0.01"),   // tickSize
            new BigDecimal("0.001"),  // stepSize
            new BigDecimal("5"),      // minNotional
            new BigDecimal("0.001"),  // minQuantity
            BigDecimal.ZERO,          // maxQuantity
            2,                        // priceScale
            3                         // quantityScale
    );

    /**
     * 将价格对齐到 tick size。
     */
    public BigDecimal alignPrice(BigDecimal price) {
        if (tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        return price.divide(tickSize, 0, RoundingMode.HALF_UP)
                .multiply(tickSize)
                .setScale(priceScale, RoundingMode.HALF_UP);
    }

    /**
     * 将数量对齐到 step size。
     */
    public BigDecimal alignQuantity(BigDecimal quantity) {
        if (stepSize.compareTo(BigDecimal.ZERO) <= 0) {
            return quantity;
        }
        return quantity.divide(stepSize, 0, RoundingMode.FLOOR)
                .multiply(stepSize)
                .setScale(quantityScale, RoundingMode.FLOOR);
    }

    /**
     * 验证订单是否满足交易所规则。
     *
     * @param price    价格
     * @param quantity 数量
     * @return null 如果通过，错误信息如果不通过
     */
    public String validate(BigDecimal price, BigDecimal quantity) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return "价格必须大于 0";
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return "数量必须大于 0";
        }
        if (minQuantity.compareTo(BigDecimal.ZERO) > 0 && quantity.compareTo(minQuantity) < 0) {
            return "数量 %.6f 低于最小值 %.6f".formatted(quantity, minQuantity);
        }
        if (maxQuantity.compareTo(BigDecimal.ZERO) > 0 && quantity.compareTo(maxQuantity) > 0) {
            return "数量 %.6f 超过最大值 %.6f".formatted(quantity, maxQuantity);
        }
        BigDecimal notional = quantity.multiply(price);
        if (minNotional.compareTo(BigDecimal.ZERO) > 0 && notional.compareTo(minNotional) < 0) {
            return "名义价值 %.2f 低于最小值 %.2f".formatted(notional, minNotional);
        }
        return null; // 通过
    }
}
