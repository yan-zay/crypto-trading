package com.tj.crypto.trading.paper.ledger;

import java.math.BigDecimal;

/** One side of a double-entry transaction. */
public record LedgerPosting(String ledgerAccount, String asset,
                            BigDecimal debit, BigDecimal credit) {
    public LedgerPosting {
        if (ledgerAccount == null || ledgerAccount.isBlank() || asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("ledgerAccount and asset are required");
        }
        debit = debit == null ? BigDecimal.ZERO : debit;
        credit = credit == null ? BigDecimal.ZERO : credit;
        if (debit.signum() < 0 || credit.signum() < 0 || debit.signum() == credit.signum()) {
            throw new IllegalArgumentException("Exactly one of debit or credit must be positive");
        }
    }

    public static LedgerPosting debit(String account, String asset, BigDecimal amount) {
        return new LedgerPosting(account, asset, amount, BigDecimal.ZERO);
    }

    public static LedgerPosting credit(String account, String asset, BigDecimal amount) {
        return new LedgerPosting(account, asset, BigDecimal.ZERO, amount);
    }
}
