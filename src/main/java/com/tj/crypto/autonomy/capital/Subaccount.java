package com.tj.crypto.autonomy.capital;

import java.util.Objects;

/** Isolated child account; its parent must exist in the signed policy topology. */
public record Subaccount(String id, String parentId, Venue venue)
        implements AccountNode, Comparable<Subaccount> {
    public Subaccount {
        id = CapitalValues.id(id, "subaccountId", 192);
        parentId = CapitalValues.id(parentId, "parentAccountId", 192);
        if (id.equals(parentId)) {
            throw new IllegalArgumentException("subaccount cannot be its own parent");
        }
        Objects.requireNonNull(venue, "venue");
    }

    @Override
    public boolean isSubaccount() {
        return true;
    }

    @Override
    public int compareTo(Subaccount other) {
        return id.compareTo(other.id);
    }
}
