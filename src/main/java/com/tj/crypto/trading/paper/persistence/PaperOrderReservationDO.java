package com.tj.crypto.trading.paper.persistence;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaperOrderReservationDO {
    private String orderId;
    private String accountId;
    private String asset;
    private String reservationType;
    private BigDecimal originalAmount;
    private BigDecimal remainingAmount;
    private BigDecimal originalQuantity;
    private BigDecimal remainingQuantity;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
