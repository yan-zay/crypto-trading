package com.tj.crypto.strategy.factor;

import java.math.BigDecimal;

/** One comparison in a factor rule group. */
public record FactorRule(
        String factorName,
        FactorOperator operator,
        FactorComparisonTarget target,
        BigDecimal threshold,
        String targetFactorName,
        BigDecimal weight
) {
    public FactorRule {
        if (factorName == null || factorName.isBlank()) {
            throw new IllegalArgumentException("factorName must not be blank");
        }
        if (operator == null) throw new IllegalArgumentException("operator is required");
        target = target == null ? FactorComparisonTarget.CONSTANT : target;
        weight = weight == null ? BigDecimal.ONE : weight;
        if (weight.signum() <= 0) throw new IllegalArgumentException("weight must be positive");
        if (target == FactorComparisonTarget.CONSTANT) {
            threshold = threshold == null ? BigDecimal.ZERO : threshold;
        }
        if (target == FactorComparisonTarget.FACTOR
                && (targetFactorName == null || targetFactorName.isBlank())) {
            throw new IllegalArgumentException(
                    "targetFactorName is required when target is FACTOR");
        }
    }
}
