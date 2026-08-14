package com.tj.crypto.trading.paper.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("paper_trade")
public class PaperTradeDO {
    @TableId(type = IdType.INPUT)
    private String tradeId;
    private String accountId;
    private String strategyId;
    private String exchange;
    private String marketType;
    private String symbol;
    private String side;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private BigDecimal grossPnl;
    private BigDecimal openFee;
    private BigDecimal closeFee;
    private BigDecimal funding;
    private BigDecimal netPnl;
    private String openOrderId;
    private String closeOrderId;
    private Long openedAtMs;
    private Long closedAtMs;
    private Long durationMs;
    private LocalDateTime createTime;
}
