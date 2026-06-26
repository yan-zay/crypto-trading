# 策略引擎 V2

> 本文档描述新策略接口（Strategy）、StrategyContext 和 SignalEvent 的设计。

## 设计理念

### 旧接口的问题

`BaseStrategy`（旧接口）：
- 依赖 `Symbol`/`Indicator` 枚举，与新的 `MarketEvent` 体系脱节
- `onEvent(Symbol, Indicator)` 无法传递事件数据
- `StrategyEngine` 使用 `instanceof` 硬编码分发，不可扩展

### 新接口的优势

`Strategy`（新接口）：
- 基于 `MarketEvent` 类型匹配，类型安全
- `onEvent(MarketEvent, StrategyContext)` 传递完整事件数据和因子查询能力
- `StrategyEngine` 按 `listenedEvents()` 自动分发，无需 `instanceof`

## 核心组件

### Strategy 接口

```java
public interface Strategy {
    String name();
    Set<Class<? extends MarketEvent>> listenedEvents();
    void onEvent(MarketEvent event, StrategyContext context);
    default void onTimer(long timestamp, StrategyContext context) {}
}
```

### StrategyContext 接口

```java
public interface StrategyContext {
    Factor getFactor(String name, Instrument instrument, Timeframe timeframe);
    List<Factor> getAllFactors(Instrument instrument, Timeframe timeframe);
}
```

### SignalEvent（交易信号）

```java
public record SignalEvent(
    String strategyName,
    Instrument instrument,
    SignalType type,        // BUY, SELL, HOLD
    BigDecimal confidence,  // 0-1
    String reason,          // 人类可读原因
    Map<String, BigDecimal> factorSnapshot,
    long timestamp
) {}
```

## 已实现的策略

### LiquidationSpikeStrategyV2

- 监听：`LiquidationEvent`
- 逻辑：大额爆仓（>$1M）时生成 HOLD 信号
- 展示：如何处理特定事件类型

### MacdCrossStrategy

- 监听：`BarEvent`（仅 closed=true）
- 逻辑：MACD 金叉（histogram 从负变正）→ BUY，死叉（从正变负）→ SELL
- 展示：如何使用因子系统查询指标

## 策略配置

```yaml
crypto:
  strategy:
    configs:
      liquidation-spike:
        enabled: true
        threshold-usd: 1000000
        symbols: [BTCUSDT, ETHUSDT]
      macd-cross:
        enabled: true
        symbols: [BTCUSDT, ETHUSDT]
```

## 添加新策略

1. 实现 `Strategy` 接口
2. 注册为 `@Component`
3. `name()` 返回唯一名称
4. `listenedEvents()` 返回监听的事件类型
5. `onEvent()` 实现策略逻辑，通过 `context` 查询因子
6. 生成 `SignalEvent` 输出信号
7. 添加单元测试

## 向后兼容

- 旧的 `BaseStrategy` 仍被支持，`StrategyEngine` 同时处理新旧两种策略
- `callOnEvent(Symbol, Indicator)` 标记为 `@Deprecated`
- 后续阶段将逐步迁移旧策略到新接口

## 不能做的事

- 不支持策略生命周期管理（CREATED → RUNNING → PAUSED → STOPPED）
- 不支持策略热加载（需要重启应用）
- 不支持信号持久化（第一阶段纯内存）
- 不支持策略间通信
