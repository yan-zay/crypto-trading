package com.tj.crypto.risk.rules;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.backtest.portfolio.VirtualAccount;
import com.tj.crypto.execution.model.Order;
import com.tj.crypto.execution.model.OrderRejectReason;
import com.tj.crypto.risk.RiskCheckResult;
import com.tj.crypto.risk.RiskRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 连续亏损冷却规则。
 * 当连续亏损达到阈值时，暂停交易一段时间。
 *
 * <p>逻辑：
 * <ol>
 *   <li>检查最近 N 笔交易是否全部亏损</li>
 *   <li>如果是，检查最后一笔亏损交易的时间</li>
 *   <li>如果冷却期尚未结束，拒绝新订单</li>
 * </ol>
 */
@Slf4j
@Component
public class CooldownRule implements RiskRule {

    private static final int DEFAULT_MAX_CONSECUTIVE_LOSSES = 5;
    private static final long DEFAULT_COOLDOWN_MINUTES = 30;

    private final int maxConsecutiveLosses;
    private final long cooldownMillis;

    public CooldownRule() {
        this(DEFAULT_MAX_CONSECUTIVE_LOSSES, DEFAULT_COOLDOWN_MINUTES);
    }

    public CooldownRule(int maxConsecutiveLosses, long cooldownMinutes) {
        this.maxConsecutiveLosses = maxConsecutiveLosses;
        this.cooldownMillis = cooldownMinutes * 60 * 1000;
    }

    @Override
    public String name() {
        return "Cooldown";
    }

    @Override
    public RiskCheckResult check(Order order, VirtualAccount account) {
        List<Trade> trades = account.getTrades();
        if (trades.size() < maxConsecutiveLosses) {
            return RiskCheckResult.passed();
        }

        // 检查最近 N 笫交易是否全部亏损
        List<Trade> recentTrades = trades.subList(
                trades.size() - maxConsecutiveLosses, trades.size());

        boolean allLosses = recentTrades.stream()
                .allMatch(t -> t.realizedPnL().signum() < 0);

        if (!allLosses) {
            return RiskCheckResult.passed();
        }

        // 检查冷却期
        Trade lastLoss = recentTrades.get(recentTrades.size() - 1);
        long elapsed = System.currentTimeMillis() - lastLoss.exitTime();

        if (elapsed < cooldownMillis) {
            long remainingSeconds = (cooldownMillis - elapsed) / 1000;
            return RiskCheckResult.rejected(
                    OrderRejectReason.COOLDOWN,
                    String.format("连续亏损 %d 次，冷却期剩余 %d 秒",
                            maxConsecutiveLosses, remainingSeconds)
            );
        }

        return RiskCheckResult.passed();
    }
}
