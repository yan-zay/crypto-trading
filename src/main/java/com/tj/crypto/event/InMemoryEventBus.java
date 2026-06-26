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
 * 关键行为：publish() 会向事件类型及其所有父类型/接口的订阅者派发。
 * 例如：发布 BarEvent 时，BarEvent.class 和 MarketEvent.class 的订阅者都会收到。
 *
 * 设计决策：
 * - 同步派发，第一阶段足够
 * - CopyOnWriteArrayList 支持并发读写（订阅者数量少，写入频率低）
 * - 异常不传播：单个处理器的异常不影响其他处理器
 */
@Slf4j
@Component
public class InMemoryEventBus implements MarketEventBus {

    private final ConcurrentHashMap<Class<? extends MarketEvent>, List<Consumer<? extends MarketEvent>>>
            subscribers = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends MarketEvent> void publish(T event) {
        // 向事件类型本身及其所有父类型/接口的订阅者派发
        Class<?> type = event.getClass();
        while (type != null && MarketEvent.class.isAssignableFrom(type)) {
            dispatchToSubscribers((Class<? extends MarketEvent>) type, event);
            // 也检查接口
            for (Class<?> iface : type.getInterfaces()) {
                if (MarketEvent.class.isAssignableFrom(iface)) {
                    dispatchToSubscribers((Class<? extends MarketEvent>) iface, event);
                }
            }
            type = type.getSuperclass();
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends MarketEvent> void dispatchToSubscribers(Class<? extends MarketEvent> eventType, T event) {
        List<Consumer<? extends MarketEvent>> handlers = subscribers.get(eventType);
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
