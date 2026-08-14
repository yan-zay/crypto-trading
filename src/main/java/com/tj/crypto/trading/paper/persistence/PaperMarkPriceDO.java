package com.tj.crypto.trading.paper.persistence;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaperMarkPriceDO {
    private String exchange;
    private String marketType;
    private String symbol;
    private BigDecimal price;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal baseVolume;
    private Long eventTimeMs;
    private String source;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
