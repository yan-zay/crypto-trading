package com.tj.crypto.execution;

import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.model.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FixedSlippageModelTest {

    private FixedSlippageModel model;

    @BeforeEach
    void setUp() {
        model = new FixedSlippageModel(10); // 10 bps = 0.1%
    }

    @Test
    @DisplayName("买入应价格上滑")
    void shouldIncreasePriceForBuy() {
        BigDecimal result = model.applySlippage(BigDecimal.valueOf(10000), OrderSide.LONG, OrderType.MARKET);
        // 10000 * 1.001 = 10010
        assertThat(result).isGreaterThan(BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("卖出应价格下滑")
    void shouldDecreasePriceForSell() {
        BigDecimal result = model.applySlippage(BigDecimal.valueOf(10000), OrderSide.SHORT, OrderType.MARKET);
        // 10000 * 0.999 = 9990
        assertThat(result).isLessThan(BigDecimal.valueOf(10000));
    }

    @Test
    @DisplayName("限价单不应应用滑点")
    void shouldNotApplySlippageForLimitOrder() {
        BigDecimal result = model.applySlippage(BigDecimal.valueOf(10000), OrderSide.LONG, OrderType.LIMIT);
        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(10000));
    }
}
