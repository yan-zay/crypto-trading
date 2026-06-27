package com.tj.crypto.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 风控配置 DTO。
 * 展示当前生效的风险控制参数。
 */
@Data
@Builder
public class RiskConfigDTO {

    /** 单笔最大亏损占比（%） */
    private BigDecimal maxLossPerTradePct;

    /** 每日最大亏损占比（%） */
    private BigDecimal maxDailyLossPct;

    /** 最大持仓占比（%） */
    private BigDecimal maxSizePct;

    /** 滑点基点（1 bp = 0.01%） */
    private int slippageBps;
}
