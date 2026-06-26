package com.tj.crypto.pojo.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @Author zay
 * @Date 2025/9/18 17:54
 */
@Data
public class LiquidationOrderSum {

    private long time;         // 秒级时间戳
    private BigDecimal longLiquidationUsd;  // 该秒多头爆仓总额
    private BigDecimal shortLiquidationUsd; // 该秒空头爆仓总额
    private BigDecimal totalLiquidationUsd; // 该秒总爆仓总额
    private int orderCount;         // 该秒爆仓订单数
    private LocalDateTime createTime;       // 创建时间
    private LocalDateTime updateTime;       // 更新时间


    /**
     * 添加爆仓金额到聚合数据
     */
    public void sum(BigDecimal usdValue, Integer side) {
        this.totalLiquidationUsd = this.totalLiquidationUsd.add(usdValue);

        if (side == 1) { // 多单爆仓
            this.longLiquidationUsd = this.longLiquidationUsd.add(usdValue);
        } else if (side == 2) { // 空单爆仓
            this.shortLiquidationUsd = this.shortLiquidationUsd.add(usdValue);
        }
    }
}
