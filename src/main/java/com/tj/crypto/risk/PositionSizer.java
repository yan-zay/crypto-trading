package com.tj.crypto.risk;

import com.tj.crypto.backtest.portfolio.TradingAccount;
import com.tj.crypto.strategy.core.SignalEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 仓位管理器。
 * 根据信号置信度和风控规则计算建议仓位大小。
 *
 * 计算逻辑：
 * 1. 基础仓位 = 账户余额 * 最大持仓占比（从 RiskProperties 读取）
 * 2. 根据信号置信度调整（confidence 0-1）
 * 3. 转换为数量 = 调整后金额 / 当前价格
 */
@Slf4j
@Component
public class PositionSizer {

    private final RiskProperties riskProperties;

    public PositionSizer(RiskProperties riskProperties) {
        this.riskProperties = riskProperties;
    }

    /**
     * 计算建议仓位数量。
     *
     * @param signal        交易信号
     * @param account       当前账户
     * @param currentPrice  当前价格
     * @return 建议数量（已考虑置信度和风控）
     */
    public BigDecimal calculateSize(SignalEvent signal, TradingAccount account, BigDecimal currentPrice) {
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal maxSizePct = riskProperties.getMaxSizePct();

        // 基础仓位 = 余额 * 最大持仓占比
        BigDecimal maxAmount = account.getBalance()
                .multiply(maxSizePct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // 根据置信度调整
        BigDecimal adjustedAmount = maxAmount.multiply(signal.confidence());

        // 转换为数量
        BigDecimal quantity = adjustedAmount.divide(currentPrice, 6, RoundingMode.HALF_UP);

        log.debug("PositionSizer: balance=${}, maxPct={}%, confidence={}, quantity={}",
                account.getBalance(), maxSizePct, signal.confidence(), quantity);

        return quantity;
    }
}
