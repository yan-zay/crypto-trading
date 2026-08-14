package com.tj.crypto.trading.paper.persistence;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaperEquitySnapshotDO {
    private String snapshotId;
    private String accountId;
    private Long eventTimeMs;
    private BigDecimal balance;
    private BigDecimal availableBalance;
    private BigDecimal lockedMargin;
    private BigDecimal unrealizedPnl;
    private BigDecimal equity;
    private LocalDateTime createTime;
}
