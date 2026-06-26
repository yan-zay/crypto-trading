package com.tj.crypto.event;

import com.tj.crypto.marketdata.model.MarketEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内事件总线实现。
 * 使用 ConcurrentHashMap 存储按事件类型分组的订阅者。
 *
 * 设计决策：
 * - 同步派发（与现有 EventBus 行为一致），第一阶段足够
 * - CopyOnWriteArrayList 支持并发读写（订阅者数量少，写入频率低）
 * - 异常不传播：单个处理器的异常不影响其他处理器
 * - 接口预留：未来可替换为 Kafka 实现，不改变调用方代码
 */
@Slf4j
@Component
public class InMemoryEventBus implements MarketEventBus {

    private final ConcurrentHashMap<Class<? extends MarketEvent>, List<Consumer<? extends MarketEvent>>>
            subscribers = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends MarketEvent> void publish(T event) {
        List<Consumer<? extends MarketEvent>> handlers = subscribers.get(event.getClass());
        if (handlers != null) {
            for (Consumer<? extends MarketEvent> handler : handlers) {
                try {
                    ((Consumer<T>) handler).accept(event);
                } catch (Exception e) {
                    log.error("Event handler error for {}: {}", event.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public <T extends MarketEvent> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(handler);
        log.debug("Subscribed to event type: {}", eventType.getSimpleName());
    }
}
