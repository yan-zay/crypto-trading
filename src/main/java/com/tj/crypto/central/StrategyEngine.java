package com.tj.crypto.central;

import com.tj.crypto.enums.Indicator;
import com.tj.crypto.enums.Symbol;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Author zay
 * @Date 2025/9/17 16:49
 */
@Slf4j
@Component
@AllArgsConstructor
public class StrategyEngine {

    private final ThreadPoolTaskExecutor tjTaskExecutor;
    private final List<BaseStrategy> strategyList;

    private void callOnEvent(Symbol symbol, Indicator indicator) {
        strategyList.forEach(strategy -> {
            if (strategy.getListenSymbol().contains(symbol) && strategy.getListenIndicator().contains(indicator)) {
                tjTaskExecutor.execute(() -> strategy.onEvent(symbol, indicator));
            }
        });
    }
}
