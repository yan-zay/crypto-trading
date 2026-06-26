package com.tj.crypto.central;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * @Author zay
 * @Date 2025/9/17 16:48
 */
@Component
public class DataCenter {

    private final Map<String, List<BigDecimal>> klineData = new ConcurrentHashMap<>();
    private final Map<String, Map<String, BigDecimal>> indicators = new ConcurrentHashMap<>();
    private final ExecutorService persistenceExecutor = Executors.newFixedThreadPool(2);
    private final EventBus eventBus = new EventBus();

    public void updateKline(String symbol, List<BigDecimal> newBar) {
        klineData.compute(symbol, (k, v) -> {
            List<BigDecimal> list = v != null ? v : new CopyOnWriteArrayList<>();
            list.addAll(newBar);
            return list;
        });

        eventBus.publish(new NewBarEvent(symbol, newBar));
        asyncPersist(symbol, newBar);
    }

    private void asyncPersist(String symbol, List<BigDecimal> data) {
        persistenceExecutor.submit(() -> {
//            DatabaseService.save(symbol, data);
        });
    }

    public void registerIndicator(String symbol, String indicatorName, BigDecimal value) {
        indicators.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>())
                .put(indicatorName, value);
    }

    public void registerListener(String symbol, Consumer<NewBarEvent> listener) {
        eventBus.subscribe(symbol, listener);
    }

    public static class NewBarEvent {
        public final String symbol;
        public final List<BigDecimal> barData;

        public NewBarEvent(String symbol, List<BigDecimal> barData) {
            this.symbol = symbol;
            this.barData = Collections.unmodifiableList(barData);
        }
    }
}
