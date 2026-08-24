package com.tj.crypto.autonomy.capital;

import java.util.Objects;

/** Root account. The capital kernel exposes no withdrawal or permission-changing operation. */
public record Account(String id, Venue venue) implements AccountNode, Comparable<Account> {
    public Account {
        id = CapitalValues.id(id, "accountId", 192);
        Objects.requireNonNull(venue, "venue");
    }

    @Override
    public int compareTo(Account other) {
        return id.compareTo(other.id);
    }
}
