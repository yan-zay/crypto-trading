package com.tj.crypto.central;

import com.tj.crypto.enums.Indicator;
import com.tj.crypto.enums.Symbol;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * @Author zay
 * @Date 2025/9/17 16:50
 */
@Component
@AllArgsConstructor
public class MACDStrategy extends BaseStrategy {

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
        // 轮询检查未处理数据
        symbols.forEach(this::processData);
    }

    @Override
    public void onEvent(Symbol symbol, Indicator indicator) {
        // 事件驱动逻辑
        calculateSignal(symbol, indicator);
    }

    private void calculateSignal(Symbol symbol, Indicator indicator) {
        // MACD指标计算逻辑
    }

    @Override
    public void processData(Symbol symbol) {
        // 检查遗漏数据处理
    }
}
