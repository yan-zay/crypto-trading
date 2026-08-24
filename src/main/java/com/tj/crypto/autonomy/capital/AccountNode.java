package com.tj.crypto.autonomy.capital;

/** A master account or a nested isolated subaccount in one policy-owned topology. */
public sealed interface AccountNode permits Account, Subaccount {
    String id();

    Venue venue();

    default String parentId() {
        return null;
    }

    default boolean isSubaccount() {
        return false;
    }
}
