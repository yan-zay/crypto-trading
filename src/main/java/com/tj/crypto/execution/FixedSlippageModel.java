package com.tj.crypto.execution;

import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.RiskProperties;
import com.tj.crypto.execution.cost.ExecutionSimulationProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 固定滑点模型。
 * 以基点（bps）计算滑点，适用于回测。
 *
 * 滑点方向：
 * - 买入：价格上滑（成交价更高）
 * - 卖出：价格下滑（成交价更低）
 */
@Component
public class FixedSlippageModel implements SlippageModel {

    private final RiskProperties riskProperties;
    private final ExecutionSimulationProperties simulationProperties;

    public FixedSlippageModel(RiskProperties riskProperties) {
        this(riskProperties, new ExecutionSimulationProperties());
    }

    @Autowired
    public FixedSlippageModel(RiskProperties riskProperties,
                              ExecutionSimulationProperties simulationProperties) {
        this.riskProperties = riskProperties;
        this.simulationProperties = simulationProperties;
    }

    @Override
    public BigDecimal applySlippage(BigDecimal price, OrderSide side, OrderType type) {
        if (type == OrderType.LIMIT) {
            return price; // 限价单不应用滑点
        }

        int slippageBps = riskProperties.getSlippageBps();
        BigDecimal slippageFactor = BigDecimal.valueOf(slippageBps)
                .divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP);

        if (side == OrderSide.LONG) {
            // 买入：价格上滑
            return price.multiply(BigDecimal.ONE.add(slippageFactor));
        } else {
            // 卖出：价格下滑
            return price.multiply(BigDecimal.ONE.subtract(slippageFactor));
        }
    }

    @Override
    public ExecutionPricing quote(BigDecimal price, OrderSide side, OrderType type,
                                  BigDecimal requestedQuantity, BigDecimal baseVolume) {
        return quote(price, side, type, requestedQuantity, baseVolume, true);
    }

    @Override
    public ExecutionPricing quote(BigDecimal price, OrderSide side, OrderType type,
                                  BigDecimal requestedQuantity, BigDecimal baseVolume,
                                  boolean allowPartial) {
        if (type == OrderType.LIMIT || baseVolume == null || baseVolume.signum() <= 0) {
            return SlippageModel.super.quote(price, side, type, requestedQuantity, baseVolume,
                    allowPartial);
        }
        BigDecimal capacity = baseVolume.multiply(simulationProperties.getMaxParticipationRate());
        BigDecimal quantity = allowPartial ? requestedQuantity.min(capacity) : requestedQuantity;
        if (quantity.signum() <= 0) {
            return new ExecutionPricing(BigDecimal.ZERO, price, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, capacity);
        }
        BigDecimal participation = quantity.divide(baseVolume, 18, RoundingMode.HALF_UP);
        BigDecimal impact = BigDecimal.valueOf(simulationProperties.getImpactCoefficientBps().doubleValue()
                * Math.sqrt(participation.doubleValue())).min(simulationProperties.getMaxImpactBps());
        BigDecimal spread = simulationProperties.getSpreadBps()
                .divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP);
        BigDecimal fixed = BigDecimal.valueOf(riskProperties.getSlippageBps());
        BigDecimal total = spread.add(fixed).add(impact);
        BigDecimal ratio = total.divide(BigDecimal.valueOf(10_000), 18, RoundingMode.HALF_UP);
        BigDecimal fillPrice = side == OrderSide.LONG
                ? price.multiply(BigDecimal.ONE.add(ratio))
                : price.multiply(BigDecimal.ONE.subtract(ratio));
        return new ExecutionPricing(quantity, fillPrice, spread, fixed, impact,
                participation, capacity);
    }
}
