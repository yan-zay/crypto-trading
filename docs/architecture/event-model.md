# 事件模型

> 本文档描述市场数据事件模型的设计理念、结构和使用方式。

## 设计理念

### 为什么选择 sealed interface + record

1. **不可变性**：所有事件使用 Java 16+ `record`，天然不可变，线程安全
2. **类型安全**：`sealed interface` 确保编译器知道所有可能的事件类型，可在 `switch` 表达式中检查 exhaustiveness
3. **简洁性**：record 自动生成 `equals()`、`hashCode()`、`toString()`，减少样板代码
4. **模式匹配**：Java 17 支持 `instanceof` 模式匹配，方便事件类型判断

### 为什么不使用 abstract class

- 事件是纯数据载体，不需要继承行为
- record 不能继承 abstract class
- sealed interface 更适合"已知类型集合"的场景

## 事件层次结构

```
MarketEvent (sealed interface)
├── BarEvent (record) — K 线数据
├── LiquidationEvent (record) — 爆仓事件
├── FundingRateEvent (record) — 资金费率
└── OpenInterestEvent (record) — 持仓量
```

## 核心事件类型

### BarEvent

K 线事件，由 Binance kline stream 解析而来。

| 字段 | 类型 | 说明 |
|------|------|------|
| instrument | Instrument | 交易工具 |
| metadata | EventMetadata | 事件元数据 |
| timeframe | Timeframe | 时间周期（M1, M5, H1 等） |
| open/high/low/close | BigDecimal | OHLC 价格 |
| volume | BigDecimal | 基础资产成交量 |
| quoteVolume | BigDecimal | 计价资产成交量 |
| closed | boolean | 是否为完整 K 线 |

**注意**：Binance 在 K 线未完成时也会推送更新（每 250ms），`closed=false` 表示中间更新。

### LiquidationEvent

爆仓事件，由 Coinglass 或 Binance forceOrder 解析而来。

| 字段 | 类型 | 说明 |
|------|------|------|
| instrument | Instrument | 交易工具 |
| metadata | EventMetadata | 事件元数据 |
| side | OrderSide | LONG 或 SHORT |
| price | BigDecimal | 爆仓价格 |
| quantity | BigDecimal | 爆仓数量（可能为 0） |
| quantityUsd | BigDecimal | USD 计价金额 |
| exchangeName | String | 原始交易所名称 |

### FundingRateEvent

资金费率事件。

| 字段 | 类型 | 说明 |
|------|------|------|
| fundingRate | BigDecimal | 当前资金费率 |
| predictedRate | BigDecimal | 预测资金费率 |
| nextFundingTime | long | 下次结算时间（毫秒） |

### OpenInterestEvent

持仓量事件。

| 字段 | 类型 | 说明 |
|------|------|------|
| openInterest | BigDecimal | 持仓量（基础资产） |
| openInterestUsd | BigDecimal | 持仓量（USD 计价） |

## EventMetadata

所有事件共享的元数据：

| 字段 | 类型 | 说明 |
|------|------|------|
| source | Exchange | 数据来源交易所 |
| exchangeTimestamp | long | 交易所时间戳（毫秒） |
| receivedTimestamp | long | 本地接收时间戳（毫秒） |
| rawMessageId | String | 原始消息标识（可选） |

## 使用示例

### 发布事件

```java
@Inject
private MarketEventBus eventBus;

BarEvent event = new BarEvent(instrument, metadata, timeframe,
    open, high, low, close, volume, quoteVolume, true);
eventBus.publish(event);
```

### 订阅事件

```java
@Inject
private MarketEventBus eventBus;

eventBus.subscribe(BarEvent.class, event -> {
    // 处理 BarEvent
});

eventBus.subscribe(LiquidationEvent.class, event -> {
    // 处理 LiquidationEvent
});
```

### 类型判断（模式匹配）

```java
public void onMarketEvent(MarketEvent event) {
    switch (event) {
        case BarEvent bar -> processBar(bar);
        case LiquidationEvent liq -> processLiquidation(liq);
        case FundingRateEvent fr -> processFundingRate(fr);
        case OpenInterestEvent oi -> processOpenInterest(oi);
    }
}
```

## 扩展点

添加新的事件类型：
1. 创建新的 record 实现 `MarketEvent`
2. 在 `sealed interface MarketEvent` 的 `permits` 列表中添加
3. 在 Normalizer 中添加转换逻辑
4. 在策略中添加订阅

## 不能做的事

- 不支持事件优先级（第一阶段）
- 不支持事件过滤（按 symbol 过滤由订阅者自行处理）
- 不支持异步订阅（第一阶段同步派发）
- 不支持事件持久化（第一阶段纯内存）
