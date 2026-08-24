package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builds account/strategy/instrument/direction utilization views.
 *
 * <p>This class deliberately has no execution or risk-engine dependency. A report cannot grant
 * trading permission or bypass durable pre-trade reservations and hard risk checks.
 */
public final class PortfolioBudgetAnalyzer {
    private static final MathContext MC = MathContext.DECIMAL128;

    public PortfolioBudgetView analyze(PortfolioExposureSnapshot snapshot,
                                       Map<PortfolioBudgetKey, BigDecimal> limits) {
        Objects.requireNonNull(snapshot, "snapshot").requireComplete();
        Objects.requireNonNull(limits, "limits");
        if (limits.isEmpty()) throw new IllegalArgumentException("at least one budget limit is required");

        TreeMap<PortfolioBudgetKey, BigDecimal> normalizedLimits = new TreeMap<>();
        limits.forEach((key, value) -> {
            Objects.requireNonNull(key, "budget key");
            if (!snapshot.accountIds().contains(key.accountId())) {
                throw new IllegalArgumentException("budget account is outside snapshot scope: "
                        + key.accountId());
            }
            if (value == null || value.signum() <= 0) {
                throw new IllegalArgumentException("budget limit must be positive for " + key.canonicalValue());
            }
            if (normalizedLimits.put(key, value.stripTrailingZeros()) != null) {
                throw new IllegalArgumentException("duplicate budget key: " + key.canonicalValue());
            }
        });

        List<PortfolioBudgetKey> missingAccounts = snapshot.accountIds().stream()
                .map(PortfolioBudgetKey::account)
                .filter(key -> !normalizedLimits.containsKey(key))
                .toList();
        if (!missingAccounts.isEmpty()) {
            throw new IllegalArgumentException("missing configured account budgets: "
                    + missingAccounts.stream().map(PortfolioBudgetKey::canonicalValue).toList());
        }

        Map<PortfolioBudgetKey, BigDecimal> used = new TreeMap<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal longNotional = BigDecimal.ZERO;
        BigDecimal shortNotional = BigDecimal.ZERO;
        for (PortfolioExposure exposure : snapshot.exposures()) {
            Objects.requireNonNull(exposure, "exposure");
            BigDecimal notional = exposure.grossNotional();
            List<PortfolioBudgetKey> keys = keys(exposure);
            List<PortfolioBudgetKey> missing = new ArrayList<>();
            for (PortfolioBudgetKey key : keys) {
                if (!normalizedLimits.containsKey(key)) missing.add(key);
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("missing configured budgets: "
                        + missing.stream().map(PortfolioBudgetKey::canonicalValue).toList());
            }
            for (PortfolioBudgetKey key : keys) {
                used.merge(key, notional, BigDecimal::add);
            }
            gross = gross.add(notional);
            if (exposure.direction() == ExposureDirection.LONG) {
                longNotional = longNotional.add(notional);
            } else {
                shortNotional = shortNotional.add(notional);
            }
        }

        Map<PortfolioBudgetKey, PortfolioBudgetUsage> usages = new LinkedHashMap<>();
        boolean breached = false;
        for (Map.Entry<PortfolioBudgetKey, BigDecimal> configured : normalizedLimits.entrySet()) {
            BigDecimal usage = used.getOrDefault(configured.getKey(), BigDecimal.ZERO);
            BigDecimal limit = configured.getValue();
            BigDecimal remaining = limit.subtract(usage);
            BigDecimal utilization = usage.divide(limit, MC);
            boolean rowBreached = usage.compareTo(limit) > 0;
            breached |= rowBreached;
            usages.put(configured.getKey(), new PortfolioBudgetUsage(configured.getKey(), usage,
                    limit, remaining, utilization, rowBreached));
        }
        return new PortfolioBudgetView(snapshot.snapshotId(), snapshot.asOfTimeMs(),
                snapshot.valuationCurrency(), snapshot.sourceManifestSha256(), AnalyticsUse.RESEARCH_ONLY,
                gross, longNotional, shortNotional, usages, breached);
    }

    private List<PortfolioBudgetKey> keys(PortfolioExposure exposure) {
        return List.of(
                PortfolioBudgetKey.account(exposure.accountId()),
                PortfolioBudgetKey.strategy(exposure.accountId(), exposure.strategyId()),
                PortfolioBudgetKey.instrument(exposure.accountId(), exposure.instrumentId()),
                PortfolioBudgetKey.direction(exposure.accountId(), exposure.direction()));
    }
}
