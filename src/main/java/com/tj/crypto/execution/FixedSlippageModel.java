package com.tj.crypto.execution;

import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.OrderType;
import lombok.AllArgsConstructor;
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
@AllArgsConstructor
public class FixedSlippageModel implements SlippageModel {

    /** 滑点基点（1 bp = 0.01%），默认 5 bps */
    private int slippageBps = 5;

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

    public void setSlippageBps(int slippageBps) {
        this.slippageBps = slippageBps;
    }
}
