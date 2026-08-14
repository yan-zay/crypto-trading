package com.tj.crypto.execution;

import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.execution.cost.ExecutionSimulationProperties;
import com.tj.crypto.execution.model.OrderType;
import com.tj.crypto.risk.RiskProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeAwareExecutionPricingTest {
    @Test
    void clipsOpenQuantityAndAppliesAdverseSpreadAndImpact() {
        RiskProperties risk = new RiskProperties();
        risk.setSlippageBps(5);
        ExecutionSimulationProperties simulation = new ExecutionSimulationProperties();
        simulation.setMaxParticipationRate(new BigDecimal("0.05"));
        simulation.setSpreadBps(new BigDecimal("2"));
        simulation.setImpactCoefficientBps(new BigDecimal("20"));
        FixedSlippageModel model = new FixedSlippageModel(risk, simulation);

        ExecutionPricing pricing = model.quote(new BigDecimal("100"), OrderSide.LONG,
                OrderType.MARKET, new BigDecimal("10"), new BigDecimal("100"), true);

        assertThat(pricing.filledQuantity()).isEqualByComparingTo("5");
        assertThat(pricing.participationRate()).isEqualByComparingTo("0.05");
        assertThat(pricing.fillPrice()).isGreaterThan(new BigDecimal("100"));
        assertThat(pricing.impactBps()).isPositive();
    }

    @Test
    void closePricingDoesNotPretendPartialCloseWhenAccountIsNetPositionBased() {
        FixedSlippageModel model = new FixedSlippageModel(new RiskProperties(),
                new ExecutionSimulationProperties());

        ExecutionPricing pricing = model.quote(new BigDecimal("100"), OrderSide.SHORT,
                OrderType.MARKET, new BigDecimal("10"), BigDecimal.ONE, false);

        assertThat(pricing.filledQuantity()).isEqualByComparingTo("10");
        assertThat(pricing.fillPrice()).isLessThan(new BigDecimal("100"));
    }
}
