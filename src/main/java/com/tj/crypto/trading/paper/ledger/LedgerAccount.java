package com.tj.crypto.trading.paper.ledger;

/** Stable account codes used by the paper double-entry ledger. */
public final class LedgerAccount {
    public static final String CASH_AVAILABLE = "CASH_AVAILABLE";
    public static final String MARGIN_LOCKED = "MARGIN_LOCKED";
    public static final String EXTERNAL_CAPITAL = "EXTERNAL_CAPITAL";
    public static final String ASSET_CLEARING = "ASSET_CLEARING";
    public static final String REALIZED_PNL = "REALIZED_PNL";
    public static final String REALIZED_LOSS = "REALIZED_LOSS";
    public static final String FEE_EXPENSE = "FEE_EXPENSE";
    public static final String FUNDING_INCOME = "FUNDING_INCOME";
    public static final String FUNDING_EXPENSE = "FUNDING_EXPENSE";

    private LedgerAccount() {}
}
