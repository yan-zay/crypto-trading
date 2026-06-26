package com.tj.crypto.event;

import com.tj.crypto.marketdata.model.MarketEvent;

import java.util.function.Consumer;

/**
 * 市场事件总线接口。
 * 提供发布/订阅机制，解耦数据源和策略引擎。
 *
 * 设计决策：
 * - 按事件类型订阅（Class<T>），不按 symbol 过滤（symbol 过滤由订阅者自行处理）
 * - 接口设计为未来可替换为 Kafka 实现
 * - 泛型 subscribe 确保类型安全
 */
public interface MarketEventBus {

    /**
     * 发布事件到总线。
     * 所有订阅了该事件类型的处理器都会被调用。
     *
     * @param event 市场事件
     */
    <T extends MarketEvent> void publish(T event);

    /**
     * 订阅指定类型的事件。
     *
     * @param eventType 事件类型
     * @param handler   事件处理器
     */
    <T extends MarketEvent> void subscribe(Class<T> eventType, Consumer<T> handler);
}
