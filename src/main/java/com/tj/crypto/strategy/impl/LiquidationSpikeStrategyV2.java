package com.tj.crypto.strategy.impl;

import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.strategy.core.SignalEvent;
import com.tj.crypto.strategy.core.SignalType;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 爆仓峰值策略 V2。
 * 使用新 Strategy 接口，监听 LiquidationEvent，大额爆仓时生成信号。
 *
 * 与 V1 的区别：
 * - 实现 Strategy 接口而非继承 BaseStrategy
 * - 使用 StrategyContext 查询因子
 * - 输出 SignalEvent 而非日志
 * - 阈值可配置
 */
@Slf4j
@Component
public class LiquidationSpikeStrategyV2 implements Strategy {

    private BigDecimal thresholdUsd = new BigDecimal("1000000");

    @Override
    public String name() {
        return "LiquidationSpike";
    }

    @Override
    public Set<Class<? extends MarketEvent>> listenedEvents() {
        return Set.of(LiquidationEvent.class);
    }

    @Override
    public void onEvent(MarketEvent event, StrategyContext context) {
        if (event instanceof LiquidationEvent liq) {
            processLiquidation(liq);
        }
    }

    private void processLiquidation(LiquidationEvent event) {
        BigDecimal amountUsd = event.quantityUsd();
        if (amountUsd == null || amountUsd.compareTo(thresholdUsd) <= 0) {
            return;
        }

        SignalEvent signal = new SignalEvent(
                name(),
                event.instrument(),
                SignalType.HOLD, // 爆仓信号不直接产生买卖，需结合其他因子
                BigDecimal.ONE,
                String.format("大额爆仓: %s %s $%s",
                        event.instrument().symbol(),
                        event.side().getDisplayName(),
                        amountUsd.toPlainString()),
                Map.of("liquidation_usd", amountUsd),
                event.metadata().exchangeTimestamp()
        );

        log.warn("[SIGNAL] {}: {} {}", signal.strategyName(), signal.instrument().symbol(), signal.reason());
    }

    /**
     * 设置阈值（由配置调用）。
     */
    public void setThresholdUsd(BigDecimal thresholdUsd) {
        this.thresholdUsd = thresholdUsd;
    }
}
