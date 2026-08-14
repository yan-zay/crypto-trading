package com.tj.crypto.trading.reconciliation.truth;

/** Required account-wide facts before live opening risk can be promoted. */
public enum VenueTruthCapability {
    ACTIVE_ORDERS,
    RECENT_FILLS,
    BALANCES,
    POSITIONS
}
