package com.tj.crypto.strategy.factor;

import java.math.BigDecimal;
import java.util.List;

/** Immutable group of entry or exit rules. */
public record FactorRuleGroup(
        FactorMatchMode mode,
        BigDecimal minimumMatchRatio,
        List<FactorRule> rules
) {
    public FactorRuleGroup {
        mode = mode == null ? FactorMatchMode.ALL : mode;
        minimumMatchRatio = minimumMatchRatio == null ? BigDecimal.ONE : minimumMatchRatio;
        if (minimumMatchRatio.signum() < 0
                || minimumMatchRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("minimumMatchRatio must be between 0 and 1");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("A factor rule group requires at least one rule");
        }
        rules = List.copyOf(rules);
    }
}
