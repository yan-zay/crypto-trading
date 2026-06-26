package com.tj.crypto.backtest.portfolio;

import com.tj.crypto.common.domain.OrderSide;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Maker/Taker 手续费模型。
 *
 * 费率（可通过 crypto.fee.* 配置）：
 * - Maker: 0.02%（默认）
 * - Taker: 0.04%（默认）
 *
 * 回测中默认按 Taker 费率计算（市场订单）。
 */
@Component
public class MakerTakerFeeModel implements FeeModel {

    private static final int SCALE = 8;

    private final BigDecimal makerFeeRate;
    private final BigDecimal takerFeeRate;

    public MakerTakerFeeModel(FeeProperties feeProperties) {
        this.makerFeeRate = feeProperties.getMakerFeeRate();
        this.takerFeeRate = feeProperties.getTakerFeeRate();
    }

    /**
     * 使用自定义费率创建（用于测试）。
     */
    public MakerTakerFeeModel(BigDecimal makerFeeRate, BigDecimal takerFeeRate) {
        this.makerFeeRate = makerFeeRate;
        this.takerFeeRate = takerFeeRate;
    }

    @Override
    public BigDecimal calculateFee(OrderSide side, BigDecimal quantity, BigDecimal price) {
        BigDecimal notional = quantity.multiply(price);
        return notional.multiply(takerFeeRate).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算 Maker 手续费。
     */
    public BigDecimal calculateMakerFee(OrderSide side, BigDecimal quantity, BigDecimal price) {
        BigDecimal notional = quantity.multiply(price);
        return notional.multiply(makerFeeRate).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算 Taker 手续费。
     */
    public BigDecimal calculateTakerFee(OrderSide side, BigDecimal quantity, BigDecimal price) {
        BigDecimal notional = quantity.multiply(price);
        return notional.multiply(takerFeeRate).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal getMakerFeeRate() { return makerFeeRate; }
    public BigDecimal getTakerFeeRate() { return takerFeeRate; }
}
