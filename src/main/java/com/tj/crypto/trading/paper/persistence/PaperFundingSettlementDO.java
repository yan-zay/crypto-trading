package com.tj.crypto.trading.paper.persistence;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaperFundingSettlementDO {
    private String fundingEventId;
    private String accountId;
    private String positionId;
    private String exchange;
    private String symbol;
    private BigDecimal fundingRate;
    private BigDecimal markPrice;
    private BigDecimal fundingAmount;
    private Long eventTimeMs;
    private LocalDateTime createTime;
}
