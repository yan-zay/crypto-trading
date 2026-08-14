package com.tj.crypto.strategy.factor;

import com.tj.crypto.common.domain.MarketSeriesKey;
import com.tj.crypto.factor.core.Factor;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime strategy assembled from user-supplied factor rule groups. */
public class CompositeFactorStrategy implements Strategy {

    static final String PRICE = "PRICE";

    private final FactorStrategySpec spec;
    private final Map<MarketSeriesKey, PositionState> states = new ConcurrentHashMap<>();
    private final Map<MarketSeriesKey, Map<String, BigDecimal>> previousValues =
            new ConcurrentHashMap<>();

    public CompositeFactorStrategy(FactorStrategySpec spec) {
        this.spec = spec;
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public Set<Class<? extends MarketEvent>> listenedEvents() {
        return Set.of(BarEvent.class);
    }

    @Override
    public Object configuration() {
        return spec;
    }

    @Override
    public SignalEvent onEvent(MarketEvent event, StrategyContext context) {
        if (!(event instanceof BarEvent bar) || !bar.closed()) return null;
        MarketSeriesKey key = MarketSeriesKey.of(bar.instrument(), bar.timeframe());
        Map<String, BigDecimal> current = loadCurrentValues(bar, context);
        Map<String, BigDecimal> previous = previousValues.getOrDefault(key, Map.of());
        PositionState state = states.getOrDefault(key, PositionState.FLAT);

        SignalDecision decision = switch (state) {
            case LONG -> decision(spec.longExit(), SignalType.SELL, PositionState.FLAT,
                    "long-exit", current, previous);
            case SHORT -> decision(spec.shortExit(), SignalType.BUY, PositionState.FLAT,
                    "short-exit", current, previous);
            case FLAT -> entryDecision(current, previous);
        };
        previousValues.put(key, Map.copyOf(current));
        if (decision == null) return null;
        states.put(key, decision.nextState());
        return new SignalEvent(
                name(), bar.instrument(), decision.signalType(), decision.confidence(),
                decision.reason(), Map.copyOf(current),
                bar.metadata().exchangeTimestamp());
    }

    private SignalDecision entryDecision(Map<String, BigDecimal> current,
                                         Map<String, BigDecimal> previous) {
        SignalDecision longDecision = decision(spec.longEntry(), SignalType.BUY,
                PositionState.LONG, "long-entry", current, previous);
        if (longDecision != null) return longDecision;
        if (spec.positionMode() == FactorPositionMode.LONG_SHORT) {
            return decision(spec.shortEntry(), SignalType.SELL,
                    PositionState.SHORT, "short-entry", current, previous);
        }
        return null;
    }

    private SignalDecision decision(FactorRuleGroup group, SignalType signalType,
                                    PositionState nextState, String label,
                                    Map<String, BigDecimal> current,
                                    Map<String, BigDecimal> previous) {
        if (group == null) return null;
        GroupResult result = evaluate(group, current, previous);
        if (!result.matched()) return null;
        String reason = label + " matched " + result.matchedRules()
                + "/" + group.rules().size() + " factor rules";
        return new SignalDecision(signalType, nextState, result.score(), reason);
    }

    private Map<String, BigDecimal> loadCurrentValues(BarEvent bar, StrategyContext context) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put(PRICE, bar.close());
        for (String factorName : spec.factorNames()) {
            Factor factor = context.getFactor(factorName, bar.instrument(), bar.timeframe());
            if (factor != null && factor.isUsable()) values.put(factorName, factor.value());
        }
        return values;
    }

    private GroupResult evaluate(FactorRuleGroup group,
                                 Map<String, BigDecimal> current,
                                 Map<String, BigDecimal> previous) {
        int matches = 0;
        BigDecimal matchedWeight = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (FactorRule rule : group.rules()) {
            totalWeight = totalWeight.add(rule.weight());
            if (matches(rule, current, previous)) {
                matches++;
                matchedWeight = matchedWeight.add(rule.weight());
            }
        }
        BigDecimal score = totalWeight.signum() == 0 ? BigDecimal.ZERO
                : matchedWeight.divide(totalWeight, 8, RoundingMode.HALF_UP);
        boolean matched = switch (group.mode()) {
            case ALL -> matches == group.rules().size();
            case ANY -> matches > 0;
            case WEIGHTED -> score.compareTo(group.minimumMatchRatio()) >= 0;
        };
        return new GroupResult(matched, score, matches);
    }

    private boolean matches(FactorRule rule,
                            Map<String, BigDecimal> current,
                            Map<String, BigDecimal> previous) {
        BigDecimal left = current.get(rule.factorName());
        BigDecimal right = targetValue(rule, current);
        if (left == null || right == null) return false;
        int comparison = left.compareTo(right);
        return switch (rule.operator()) {
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case CROSS_ABOVE -> {
                BigDecimal previousLeft = previous.get(rule.factorName());
                BigDecimal previousRight = targetValue(rule, previous);
                yield previousLeft != null && previousRight != null
                        && previousLeft.compareTo(previousRight) < 0 && comparison >= 0;
            }
            case CROSS_BELOW -> {
                BigDecimal previousLeft = previous.get(rule.factorName());
                BigDecimal previousRight = targetValue(rule, previous);
                yield previousLeft != null && previousRight != null
                        && previousLeft.compareTo(previousRight) > 0 && comparison <= 0;
            }
        };
    }

    private BigDecimal targetValue(FactorRule rule, Map<String, BigDecimal> values) {
        return switch (rule.target()) {
            case CONSTANT -> rule.threshold();
            case PRICE -> values.get(PRICE);
            case FACTOR -> values.get(rule.targetFactorName());
        };
    }

    private enum PositionState { FLAT, LONG, SHORT }

    private record GroupResult(boolean matched, BigDecimal score, int matchedRules) {}

    private record SignalDecision(SignalType signalType, PositionState nextState,
                                  BigDecimal confidence, String reason) {}
}
