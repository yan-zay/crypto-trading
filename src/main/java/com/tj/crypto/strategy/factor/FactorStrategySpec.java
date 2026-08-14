package com.tj.crypto.strategy.factor;

import java.util.LinkedHashSet;
import java.util.Set;

/** Complete, serializable definition of a single-factor or multi-factor strategy. */
public record FactorStrategySpec(
        String name,
        FactorPositionMode positionMode,
        FactorRuleGroup longEntry,
        FactorRuleGroup longExit,
        FactorRuleGroup shortEntry,
        FactorRuleGroup shortExit
) {
    public FactorStrategySpec {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("Strategy name must contain 1 to 100 characters");
        }
        positionMode = positionMode == null ? FactorPositionMode.LONG_ONLY : positionMode;
        if (longEntry == null || longExit == null) {
            throw new IllegalArgumentException("longEntry and longExit are required");
        }
        if (positionMode == FactorPositionMode.LONG_SHORT
                && (shortEntry == null || shortExit == null)) {
            throw new IllegalArgumentException(
                    "shortEntry and shortExit are required for LONG_SHORT mode");
        }
    }

    public Set<String> factorNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addFactors(names, longEntry);
        addFactors(names, longExit);
        addFactors(names, shortEntry);
        addFactors(names, shortExit);
        return Set.copyOf(names);
    }

    private void addFactors(Set<String> names, FactorRuleGroup group) {
        if (group == null) return;
        for (FactorRule rule : group.rules()) {
            names.add(rule.factorName());
            if (rule.target() == FactorComparisonTarget.FACTOR) {
                names.add(rule.targetFactorName());
            }
        }
    }
}
