package com.tj.crypto.risk;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 风控配置属性。
 * 绑定 crypto.risk.* 前缀的配置项。
 *
 * 配置示例：
 * crypto:
 *   risk:
 *     max-loss-per-trade-pct: 2.0
 *     max-daily-loss-pct: 5.0
 *     max-size-pct: 30.0
 *     slippage-bps: 5
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.risk")
public class RiskProperties {

    /** 单笔最大亏损占比（%），默认 2% */
    private BigDecimal maxLossPerTradePct = BigDecimal.valueOf(2.0);

    /** 每日最大亏损占比（%），默认 5% */
    private BigDecimal maxDailyLossPct = BigDecimal.valueOf(5.0);

    /** 最大持仓占比（%），默认 30% */
    private BigDecimal maxSizePct = BigDecimal.valueOf(30.0);

    /** 滑点基点（1 bp = 0.01%），默认 5 bps */
    private int slippageBps = 5;
}
