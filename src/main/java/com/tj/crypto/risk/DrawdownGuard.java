package com.tj.crypto.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 最大回撤守卫。
 * 监控账户回撤，超过阈值自动触发 KillSwitch（CLOSE_ONLY 模式）。
 *
 * <p>计算公式：drawdownPct = (peakEquity - currentEquity) / peakEquity * 100
 *
 * <p>典型用法：
 * <pre>
 *   if (guard.checkDrawdown(currentEquity, peakEquity)) {
 *       // 回撤已超限，KillSwitch 已自动触发
 *   }
 * </pre>
 */
@Slf4j
@Component
public class DrawdownGuard {

    private static final BigDecimal DEFAULT_MAX_DRAWDOWN_PCT = BigDecimal.valueOf(20);
    private static final int SCALE = 4;

    private final BigDecimal maxDrawdownPct;
    private final KillSwitch killSwitch;

    @Autowired
    public DrawdownGuard(KillSwitch killSwitch) {
        this(killSwitch, DEFAULT_MAX_DRAWDOWN_PCT);
    }

    public DrawdownGuard(KillSwitch killSwitch, BigDecimal maxDrawdownPct) {
        this.killSwitch = killSwitch;
        this.maxDrawdownPct = maxDrawdownPct;
    }

    /**
     * 检查回撤是否超过阈值。
     * 超过时自动触发 KillSwitch（CLOSE_ONLY 模式）。
     *
     * @param currentEquity 当前权益
     * @param peakEquity    历史最高权益
     * @return true 如果回撤超过阈值（已触发熔断），false 如果在安全范围内
     */
    public boolean checkDrawdown(BigDecimal currentEquity, BigDecimal peakEquity) {
        if (peakEquity == null || peakEquity.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal drawdownPct = peakEquity.subtract(currentEquity)
                .divide(peakEquity, SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (drawdownPct.compareTo(maxDrawdownPct) >= 0) {
            log.warn("[DRAWDOWN_GUARD] Drawdown {}% exceeded threshold {}%. Activating CLOSE_ONLY.",
                    drawdownPct.setScale(2, RoundingMode.HALF_UP), maxDrawdownPct);
            killSwitch.activate(KillSwitch.Mode.CLOSE_ONLY,
                    "MAX_DRAWDOWN_BREACH:" + drawdownPct.toPlainString(), "DRAWDOWN_GUARD");
            return true;
        }

        log.debug("[DRAWDOWN_GUARD] Drawdown {}% within limit {}%",
                drawdownPct.setScale(2, RoundingMode.HALF_UP), maxDrawdownPct);
        return false;
    }

    public BigDecimal getMaxDrawdownPct() {
        return maxDrawdownPct;
    }
}
