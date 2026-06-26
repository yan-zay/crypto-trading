# 市场数据连接器

> 本文档描述连接器抽象、Normalizer 模式和数据流设计。

## 连接器架构

```
外部数据源 (WebSocket/REST)
        │
        ▼
MarketDataConnector (接口)
  connect() / disconnect() / subscribe() / health() / onEvent()
        │
        ▼
Normalizer (标准化器)
  原始 JSON/Payload → MarketEvent
        │
        ▼
MarketEventBus (事件总线)
  publish(MarketEvent)
        │
        ▼
StrategyEngine → BaseStrategy
```

## MarketDataConnector 接口

```java
public interface MarketDataConnector {
    void connect();
    void disconnect();
    boolean isConnected();
    void subscribe(SubscriptionRequest request);
    void unsubscribe(SubscriptionRequest request);
    ConnectorHealth health();
    void onEvent(Consumer<MarketEvent> handler);
}
```

### 设计决策

- **接口而非抽象类**：不同交易所的连接逻辑差异大，接口更灵活
- **Consumer 回调**：保持简单，第一阶段用进程内事件总线
- **health() 返回 record**：不可变，可安全跨线程传递

## Normalizer 模式

每个数据源有一个对应的 Normalizer，负责将原始 payload 转换为内部 MarketEvent。

### BinanceKlineNormalizer

- 输入：Binance kline WebSocket JSON 中的 "k" 节点
- 输出：BarEvent
- 处理：symbol 解析、时间周期映射、价格/成交量转换

### CoinglassLiquidationNormalizer

- 输入：Coinglass LiquidationOrder DTO
- 输出：LiquidationEvent
- 处理：side 映射（1=LONG, 2=SHORT）、金额转换

### 为什么需要 Normalizer

1. **隔离变化**：交易所 API 变更只影响对应的 Normalizer
2. **类型安全**：原始 JSON 转为强类型 record
3. **可测试**：Normalizer 可独立单元测试，不依赖网络
4. **可复用**：同一 Normalizer 可用于实时和回测数据

## 当前实现状态

### 已实现

| 组件 | 状态 | 说明 |
|------|------|------|
| BinanceKlineNormalizer | ✅ | kline JSON → BarEvent |
| CoinglassLiquidationNormalizer | ✅ | LiquidationOrder → LiquidationEvent |
| MarketDataConnector 接口 | ✅ | 定义完成 |
| SubscriptionRequest | ✅ | 不可变 record |
| ConnectorHealth | ✅ | 不可变 record |

### 未实现（后续阶段）

| 组件 | 计划阶段 | 说明 |
|------|---------|------|
| BinanceConnector 实现 | 第二阶段 | 包装现有 BinanceWebSocketClient |
| CoinglassConnector 实现 | 第二阶段 | 包装现有 CoinglassWebSocketClient |
| OKXConnector | 第二阶段 | 新增 |
| 订阅注册与重连回放 | 第二阶段 | 连接器级别的订阅管理 |
| REST 历史数据回填 | 第三阶段 | 用于回测 |

## 数据流

### 实时数据流（当前）

```
Coinglass WS → CoinglassWebSocketClient.handleData()
  ├── 本地聚合（1min/5min KLineData 队列）— 保留
  └── CoinglassLiquidationNormalizer → LiquidationEvent → MarketEventBus

Binance WS → BinanceWebSocketClient.handleKlineData()
  └── BinanceKlineNormalizer → BarEvent → MarketEventBus

MarketEventBus → StrategyEngine.onMarketEvent()
  └── LiquidationSpikeStrategy.onMarketEvent()
```

### 扩展点

- 添加新的 Normalizer：实现从原始 payload 到 MarketEvent 的转换
- 添加新的 Connector：实现 MarketDataConnector 接口
- 添加新的事件类型：在 MarketEvent sealed interface 中添加

## 不能做的事

- 不支持自动重连管理（第二阶段）
- 不支持订阅状态持久化（第二阶段）
- 不支持多数据源合并（第二阶段）
- 不支持 rate limit 管理（第二阶段）
