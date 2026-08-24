package com.tj.crypto.portfolio.analytics;

/** Payoff convention supported by deterministic portfolio analytics. */
public enum ExposureValuationModel {
    /** Quantity is base quantity and PnL/notional settle linearly in the quote currency. */
    LINEAR_QUOTE_SETTLED
}
