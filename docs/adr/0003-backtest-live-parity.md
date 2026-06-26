# ADR-0003: 回测/实盘一致性设计

## 状态

已接受

## 背景

回测和实盘交易使用不同的数据源，但策略逻辑必须完全一致，否则回测结果不可信。

## 决策

回测、模拟交易、实盘交易共享以下组件：
- `Strategy` 接口（完全相同的 `onEvent()` 方法）
- `MarketEvent` 模型（相同的事件类型）
- `MarketEventBus`（相同的发布/订阅机制）
- `VirtualAccount`（相同的持仓和交易逻辑）

只有数据源不同：
- 回测：`HistoricalDataProvider` → `EventReplayer` → `InMemoryEventBus`
- 模拟：实时 WebSocket → `InMemoryEventBus`
- 实盘：实时 WebSocket → `InMemoryEventBus`

## 原因

1. **策略代码零修改**：同一份策略代码在回测和实盘中运行
2. **因子计算一致**：使用相同的 `BarCache` 和 `FactorCalculator`
3. **交易逻辑一致**：使用相同的 `VirtualAccount` 开平仓逻辑

## 影响

### 正面

- 回测结果可直接指导实盘
- 策略开发迭代快（回测验证 → 模拟验证 → 实盘）

### 负面

- 回测不能模拟滑点、手续费、资金费率（后续阶段补充）
- 回测使用同步执行，可能与实盘异步行为有差异
