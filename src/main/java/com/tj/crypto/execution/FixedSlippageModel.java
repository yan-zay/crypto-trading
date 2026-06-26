package com.tj.crypto.execution;

import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.RiskProperties;
import org.springframework.stereotype.Component;

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

    private final int slippageBps;

    public FixedSlippageModel(RiskProperties riskProperties) {
        this.slippageBps = riskProperties.getSlippageBps();
    }

    @Override
    public BigDecimal applySlippage(BigDecimal price, OrderSide side, OrderType type) {
        if (type == OrderType.LIMIT) {
            return price; // 限价单不应用滑点
        }

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
}
