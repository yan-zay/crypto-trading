package com.tj.crypto.central;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * @Author zay
 * @Date 2025/9/17 16:48
 */
public class EventBus {

    private final Map<String, List<Consumer<DataCenter.NewBarEvent>>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String symbol, Consumer<DataCenter.NewBarEvent> listener) {
        subscribers.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    public void publish(DataCenter.NewBarEvent event) {
        List<Consumer<DataCenter.NewBarEvent>> listeners = subscribers.get(event.symbol);
        if (listeners != null) {
            listeners.forEach(listener -> listener.accept(event));
        }
    }
}
