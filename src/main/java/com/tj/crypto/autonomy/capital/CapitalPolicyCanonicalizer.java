package com.tj.crypto.autonomy.capital;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

final class CapitalPolicyCanonicalizer {
    private final StringBuilder value = new StringBuilder("capital-risk-policy-v1");

    byte[] encode(CapitalRiskPolicy policy) {
        add(policy.policyId());
        add(Long.toString(policy.version()));
        add(policy.portfolioId());
        add(policy.revisionKind().name());
        add(policy.predecessorSha256() == null ? "" : policy.predecessorSha256());
        add(policy.issuedAt());
        add(policy.effectiveAt());
        add(policy.expiresAt());
        add(policy.portfolioCapital());
        add(Integer.toString(policy.accounts().size()));
        for (AccountNode account : policy.accounts()) {
            add(account.isSubaccount() ? "SUBACCOUNT" : "ACCOUNT");
            add(account.id());
            add(account.parentId() == null ? "" : account.parentId());
            add(account.venue().id());
        }
        add(Integer.toString(policy.accountAllocations().size()));
        for (AccountCapitalAllocation allocation : policy.accountAllocations()) {
            add(allocation.accountId());
            add(allocation.allocatedCapital());
        }
        add(Integer.toString(policy.riskBudgets().size()));
        for (ScopedRiskBudget budget : policy.riskBudgets()) {
            RiskScope scope = budget.scope();
            add(scope.accountId());
            add(scope.venue().id());
            add(scope.instrument().key().assetClass().code());
            add(scope.instrument().key().namespace());
            add(scope.instrument().key().identifier());
            add(scope.instrument().specification().sha256());
            add(scope.strategyId());
            RiskLimits limits = budget.limits();
            add(limits.maxGrossNotional());
            add(limits.maxNetNotional());
            add(limits.maxSingleOrderNotional());
            add(limits.maxDailyLoss());
            add(limits.maxLossAtStop());
            add(budget.expiresAt());
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void add(Money money) {
        add(money.amount().toPlainString());
        add(money.unit().code());
    }

    private void add(Instant instant) {
        add(Long.toString(instant.getEpochSecond()));
        add(Integer.toString(instant.getNano()));
    }

    private void add(String component) {
        value.append('|').append(component.length()).append(':').append(component);
    }
}
