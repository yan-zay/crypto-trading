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

    /** 实盘单笔订单最大名义金额占权益（%），默认 30% */
    private BigDecimal maxSizePct = BigDecimal.valueOf(30.0);

    /** 全组合最大名义敞口占权益（%） */
    private BigDecimal maxTotalExposurePct = BigDecimal.valueOf(80.0);

    /** 单品种最大名义敞口占权益（%） */
    private BigDecimal maxSymbolExposurePct = BigDecimal.valueOf(40.0);

    /** 单策略最大名义敞口占初始资金（%） */
    private BigDecimal maxStrategyBudgetPct = BigDecimal.valueOf(50.0);

    /** 实盘签名账户快照允许的最大年龄（毫秒） */
    private long accountSnapshotMaxAgeMs = 5_000;

    /** 实盘签名账户快照允许的最大未来时钟偏差（毫秒） */
    private long accountSnapshotMaxFutureSkewMs = 1_000;

    /** 滑点基点（1 bp = 0.01%），默认 5 bps */
    private int slippageBps = 5;
}
