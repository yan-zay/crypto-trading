package com.tj.crypto.autonomy.capital;

import java.util.Objects;

/** Capital assigned to a master account or subaccount. Zero is an explicit freeze. */
public record AccountCapitalAllocation(String accountId, Money allocatedCapital)
        implements Comparable<AccountCapitalAllocation> {
    public AccountCapitalAllocation {
        accountId = CapitalValues.id(accountId, "accountId", 192);
        Objects.requireNonNull(allocatedCapital, "allocatedCapital");
        if (!allocatedCapital.isNonNegative()) {
            throw new IllegalArgumentException("allocatedCapital must not be negative");
        }
    }

    @Override
    public int compareTo(AccountCapitalAllocation other) {
        return accountId.compareTo(other.accountId);
    }
}
