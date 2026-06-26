package com.tj.crypto.central;

import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.enums.Indicator;
import com.tj.crypto.enums.Symbol;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 爆仓峰值策略（示例策略）。
 * 监听大额爆仓事件，当单笔爆仓金额超过阈值时生成信号。
 *
 * 设计目的：
 * - 替代名不副实的 MACDStrategy（实际监听 LIQUIDATION）
 * - 作为策略开发的参考实现
 * - 展示如何处理 LiquidationEvent
 *
 * 不能做的事：
 * - 不做真实交易信号（阈值为硬编码示例值）
 * - 不做多时间窗口聚合
 * - 不做因子计算（第二阶段）
 */
@Slf4j
@Component
@AllArgsConstructor
public class LiquidationSpikeStrategy extends BaseStrategy {

    /** 爆仓金额阈值（USD），超过此值视为大额爆仓 */
    private static final BigDecimal SPIKE_THRESHOLD_USD = new BigDecimal("1000000");

    private final Set<Symbol> symbols = Set.of(Symbol.BTC_USDT, Symbol.ETH_USDT);
    private final Set<Indicator> indicator = Set.of(Indicator.LIQUIDATION);

    @Override
    public Set<Symbol> getListenSymbol() {
        return symbols;
    }

    @Override
    public Set<Indicator> getListenIndicator() {
        return indicator;
    }

    @Override
    public void pollingExecute() {
        // 轮询检查未处理数据（第一阶段不实现）
    }

    @Override
    public void onEvent(Symbol symbol, Indicator indicator) {
        // 旧接口，保留兼容性
    }

    /**
     * 处理标准化的市场事件。
     * 由 StrategyEngine.onMarketEvent() 调用。
     *
     * @param event 市场事件
     */
    public void onMarketEvent(MarketEvent event) {
        if (event instanceof LiquidationEvent liq) {
            processLiquidation(liq);
        }
    }

    private void processLiquidation(LiquidationEvent event) {
        BigDecimal amountUsd = event.quantityUsd();
        if (amountUsd != null && amountUsd.compareTo(SPIKE_THRESHOLD_USD) > 0) {
            log.warn("[SIGNAL] 大额爆仓检测: {} {} {} 金额=${} 价格=${}",
                    event.instrument().symbol(),
                    event.side().getDisplayName(),
                    event.exchangeName() != null ? event.exchangeName() : "unknown",
                    amountUsd.toPlainString(),
                    event.price() != null ? event.price().toPlainString() : "N/A");
        }
    }

    @Override
    public void processData(Symbol symbol) {
        // 检查遗漏数据处理（第一阶段不实现）
    }
}
