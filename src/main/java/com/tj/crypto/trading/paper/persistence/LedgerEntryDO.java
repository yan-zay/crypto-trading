package com.tj.crypto.trading.paper.persistence;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class LedgerEntryDO {
    private String entryId;
    private String transactionId;
    private String accountId;
    private String ledgerAccount;
    private String asset;
    private BigDecimal debit;
    private BigDecimal credit;
    private LocalDateTime createTime;
}
