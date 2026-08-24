package com.tj.crypto.autonomy.capital;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, content-addressed capital allocation and risk policy.
 *
 * <p>Construction validates the complete account tree, allocation conservation, exact venue
 * bindings and risk-budget units. Signature verification is intentionally outside this value
 * object and cannot be represented by a caller-provided status flag.
 */
public final class CapitalRiskPolicy {
    private final String policyId;
    private final long version;
    private final String portfolioId;
    private final PolicyRevisionKind revisionKind;
    private final String predecessorSha256;
    private final Instant issuedAt;
    private final Instant effectiveAt;
    private final Instant expiresAt;
    private final Money portfolioCapital;
    private final List<AccountNode> accounts;
    private final List<AccountCapitalAllocation> accountAllocations;
    private final List<ScopedRiskBudget> riskBudgets;
    private final Map<String, AccountNode> accountsById;
    private final Map<String, AccountCapitalAllocation> allocationsByAccount;
    private final Map<RiskScope, ScopedRiskBudget> budgetsByScope;
    private final byte[] canonicalBytes;
    private final String sha256;

    public CapitalRiskPolicy(
            String policyId,
            long version,
            String portfolioId,
            PolicyRevisionKind revisionKind,
            String predecessorSha256,
            Instant issuedAt,
            Instant effectiveAt,
            Instant expiresAt,
            Money portfolioCapital,
            List<? extends AccountNode> accounts,
            List<AccountCapitalAllocation> accountAllocations,
            List<ScopedRiskBudget> riskBudgets) {
        this.policyId = CapitalValues.id(policyId, "policyId", 192);
        if (version < 1) throw new IllegalArgumentException("policy version must be positive");
        this.version = version;
        this.portfolioId = CapitalValues.id(portfolioId, "portfolioId", 192);
        this.revisionKind = Objects.requireNonNull(revisionKind, "revisionKind");
        if (version == 1) {
            if (predecessorSha256 != null && !predecessorSha256.isBlank()) {
                throw new IllegalArgumentException("initial policy cannot have a predecessor");
            }
            if (revisionKind == PolicyRevisionKind.EMERGENCY_REDUCTION) {
                throw new IllegalArgumentException("initial policy cannot be an emergency reduction");
            }
            this.predecessorSha256 = null;
        } else {
            this.predecessorSha256 = CapitalValues.sha256(predecessorSha256, "predecessorSha256");
        }
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (issuedAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("policy cannot be effective before issuance");
        }
        if (!expiresAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("policy expiry must be after effective time");
        }
        this.portfolioCapital = Objects.requireNonNull(portfolioCapital, "portfolioCapital");
        if (!portfolioCapital.isPositive()) {
            throw new IllegalArgumentException("portfolioCapital must be positive");
        }

        this.accounts = sortedAccounts(accounts);
        this.accountsById = indexAccounts(this.accounts);
        validateTopology(this.accountsById);
        this.accountAllocations = sortedAllocations(accountAllocations);
        this.allocationsByAccount = indexAllocations(this.accountAllocations);
        validateAllocations();
        this.riskBudgets = sortedBudgets(riskBudgets);
        this.budgetsByScope = indexBudgets(this.riskBudgets);
        validateBudgets();
        this.canonicalBytes = new CapitalPolicyCanonicalizer().encode(this);
        this.sha256 = digest(canonicalBytes);
    }

    public String policyId() { return policyId; }
    public long version() { return version; }
    public String portfolioId() { return portfolioId; }
    public PolicyRevisionKind revisionKind() { return revisionKind; }
    public String predecessorSha256() { return predecessorSha256; }
    public Instant issuedAt() { return issuedAt; }
    public Instant effectiveAt() { return effectiveAt; }
    public Instant expiresAt() { return expiresAt; }
    public Money portfolioCapital() { return portfolioCapital; }
    public List<AccountNode> accounts() { return accounts; }
    public List<AccountCapitalAllocation> accountAllocations() { return accountAllocations; }
    public List<ScopedRiskBudget> riskBudgets() { return riskBudgets; }
    public String sha256() { return sha256; }

    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    public boolean currentAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !effectiveAt.isAfter(instant) && expiresAt.isAfter(instant);
    }

    AccountNode account(String accountId) {
        return accountsById.get(accountId);
    }

    AccountCapitalAllocation allocation(String accountId) {
        return allocationsByAccount.get(accountId);
    }

    ScopedRiskBudget budget(RiskScope scope) {
        return budgetsByScope.get(scope);
    }

    Map<String, AccountCapitalAllocation> allocationsByAccount() {
        return allocationsByAccount;
    }

    Map<RiskScope, ScopedRiskBudget> budgetsByScope() {
        return budgetsByScope;
    }

    private List<AccountNode> sortedAccounts(List<? extends AccountNode> source) {
        Objects.requireNonNull(source, "accounts");
        if (source.isEmpty()) throw new IllegalArgumentException("at least one account is required");
        ArrayList<AccountNode> result = new ArrayList<>(source.size());
        for (AccountNode account : source) result.add(Objects.requireNonNull(account, "account"));
        result.sort((left, right) -> left.id().compareTo(right.id()));
        return List.copyOf(result);
    }

    private Map<String, AccountNode> indexAccounts(List<AccountNode> source) {
        LinkedHashMap<String, AccountNode> result = new LinkedHashMap<>();
        for (AccountNode account : source) {
            if (result.put(account.id(), account) != null) {
                throw new IllegalArgumentException("duplicate account: " + account.id());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void validateTopology(Map<String, AccountNode> topology) {
        boolean hasRoot = false;
        for (AccountNode account : topology.values()) {
            if (!account.isSubaccount()) {
                hasRoot = true;
                continue;
            }
            AccountNode parent = topology.get(account.parentId());
            if (parent == null) {
                throw new IllegalArgumentException("missing parent account for " + account.id());
            }
            if (!parent.venue().equals(account.venue())) {
                throw new IllegalArgumentException("subaccount and parent must use the same venue");
            }
            Set<String> visited = new HashSet<>();
            AccountNode cursor = account;
            while (cursor.isSubaccount()) {
                if (!visited.add(cursor.id())) {
                    throw new IllegalArgumentException("account topology contains a cycle");
                }
                cursor = topology.get(cursor.parentId());
                if (cursor == null) {
                    throw new IllegalArgumentException("account topology does not terminate at a root");
                }
            }
        }
        if (!hasRoot) throw new IllegalArgumentException("account topology requires a root account");
    }

    private List<AccountCapitalAllocation> sortedAllocations(
            List<AccountCapitalAllocation> source) {
        Objects.requireNonNull(source, "accountAllocations");
        ArrayList<AccountCapitalAllocation> result = new ArrayList<>(source.size());
        for (AccountCapitalAllocation value : source) {
            result.add(Objects.requireNonNull(value, "accountAllocation"));
        }
        Collections.sort(result);
        return List.copyOf(result);
    }

    private Map<String, AccountCapitalAllocation> indexAllocations(
            List<AccountCapitalAllocation> source) {
        LinkedHashMap<String, AccountCapitalAllocation> result = new LinkedHashMap<>();
        for (AccountCapitalAllocation allocation : source) {
            if (result.put(allocation.accountId(), allocation) != null) {
                throw new IllegalArgumentException("duplicate account allocation: "
                        + allocation.accountId());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void validateAllocations() {
        if (!allocationsByAccount.keySet().equals(accountsById.keySet())) {
            throw new IllegalArgumentException("every account requires exactly one capital allocation");
        }
        AssetUnit unit = portfolioCapital.unit();
        Map<String, Money> childTotals = new HashMap<>();
        Money rootTotal = Money.zero(unit);
        for (AccountCapitalAllocation allocation : accountAllocations) {
            if (!allocation.allocatedCapital().unit().equals(unit)) {
                throw new IllegalArgumentException("allocation valuation unit differs from policy");
            }
            AccountNode account = accountsById.get(allocation.accountId());
            if (account.isSubaccount()) {
                childTotals.merge(account.parentId(), allocation.allocatedCapital(), Money::add);
            } else {
                rootTotal = rootTotal.add(allocation.allocatedCapital());
            }
        }
        if (rootTotal.compareTo(portfolioCapital) > 0) {
            throw new IllegalArgumentException("root allocations exceed portfolio capital");
        }
        for (Map.Entry<String, Money> children : childTotals.entrySet()) {
            Money parentCapital = allocationsByAccount.get(children.getKey()).allocatedCapital();
            if (children.getValue().compareTo(parentCapital) > 0) {
                throw new IllegalArgumentException("child allocations exceed parent capital: "
                        + children.getKey());
            }
        }
    }

    private List<ScopedRiskBudget> sortedBudgets(List<ScopedRiskBudget> source) {
        Objects.requireNonNull(source, "riskBudgets");
        ArrayList<ScopedRiskBudget> result = new ArrayList<>(source.size());
        for (ScopedRiskBudget value : source) {
            result.add(Objects.requireNonNull(value, "riskBudget"));
        }
        Collections.sort(result);
        return List.copyOf(result);
    }

    private Map<RiskScope, ScopedRiskBudget> indexBudgets(List<ScopedRiskBudget> source) {
        LinkedHashMap<RiskScope, ScopedRiskBudget> result = new LinkedHashMap<>();
        Set<String> canonicalScopes = new HashSet<>();
        for (ScopedRiskBudget budget : source) {
            if (!canonicalScopes.add(budget.scope().canonicalId())
                    || result.put(budget.scope(), budget) != null) {
                throw new IllegalArgumentException("duplicate risk scope: "
                        + budget.scope().canonicalId());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void validateBudgets() {
        for (ScopedRiskBudget budget : riskBudgets) {
            RiskScope scope = budget.scope();
            AccountNode account = accountsById.get(scope.accountId());
            if (account == null) {
                throw new IllegalArgumentException("risk scope references an unknown account");
            }
            if (!account.venue().equals(scope.venue())) {
                throw new IllegalArgumentException("risk scope venue differs from account venue");
            }
            if (!budget.limits().valuationUnit().equals(portfolioCapital.unit())) {
                throw new IllegalArgumentException("risk limits use a different valuation unit");
            }
            if (!budget.expiresAt().isAfter(effectiveAt)
                    || budget.expiresAt().isAfter(expiresAt)) {
                throw new IllegalArgumentException("risk budget expiry must be inside policy validity");
            }
        }
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
