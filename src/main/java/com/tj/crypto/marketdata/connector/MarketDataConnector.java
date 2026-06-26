package com.tj.crypto.marketdata.connector;

import com.tj.crypto.marketdata.model.MarketEvent;

import java.util.function.Consumer;

/**
 * 市场数据连接器接口。
 * 定义与外部数据源（Binance、Coinglass、OKX 等）的连接契约。
 *
 * 设计决策：
 * - 接口而非抽象类，因为不同交易所的连接逻辑差异大
 * - onEvent 使用 Consumer 回调而非返回 Flux，保持简单（第一阶段用进程内事件总线）
 * - health() 返回不可变 record，可安全跨线程传递
 */
public interface MarketDataConnector {

    /**
     * 建立连接。
     */
    void connect();

    /**
     * 断开连接。
     */
    void disconnect();

    /**
     * 是否已连接。
     */
    boolean isConnected();

    /**
     * 订阅数据频道。
     */
    void subscribe(SubscriptionRequest request);

    /**
     * 取消订阅。
     */
    void unsubscribe(SubscriptionRequest request);

    /**
     * 健康检查。
     */
    ConnectorHealth health();

    /**
     * 注册事件处理器。
     * 当连接器收到新数据时，会调用此处理器。
     */
    void onEvent(Consumer<MarketEvent> handler);
}
