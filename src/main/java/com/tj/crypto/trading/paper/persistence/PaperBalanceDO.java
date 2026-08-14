package com.tj.crypto.trading.paper.persistence;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaperBalanceDO {
    private String accountId;
    private String asset;
    private BigDecimal totalBalance;
    private BigDecimal availableBalance;
    private BigDecimal lockedBalance;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
