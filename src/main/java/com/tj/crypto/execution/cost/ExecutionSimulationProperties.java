package com.tj.crypto.execution.cost;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.execution.simulation")
public class ExecutionSimulationProperties {
    /** Full bid/ask spread in basis points. */
    private BigDecimal spreadBps = new BigDecimal("2");
    /** Maximum share of a bar's base volume available to one order. */
    private BigDecimal maxParticipationRate = new BigDecimal("0.05");
    /** Square-root impact coefficient expressed in basis points. */
    private BigDecimal impactCoefficientBps = new BigDecimal("25");
    private BigDecimal maxImpactBps = new BigDecimal("100");
    private BigDecimal latencyBpsPerSecond = new BigDecimal("0.5");
    private long maxMarkAgeMs = 300_000;
}
