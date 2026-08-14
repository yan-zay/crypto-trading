package com.tj.crypto.trading.paper.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LedgerTransactionDO {
    private String transactionId;
    private String accountId;
    private String transactionType;
    private String referenceType;
    private String referenceId;
    private Long eventTimeMs;
    private String description;
    private LocalDateTime createTime;
}
