# 回测引擎

> 本文档描述回测引擎的设计、事件回放和性能报告。

## 设计理念

### 核心约束

**回测、模拟交易、实盘交易必须复用同一套 Strategy 接口和 MarketEvent 模型。** 只有数据源不同：
- 回测：从历史数据文件回放
- 模拟：从实时 WebSocket 接收（虚拟资金）
- 实盘：从实时 WebSocket 接收（真实资金）

## 架构

```
HistoricalDataProvider → EventReplayer → InMemoryEventBus → Strategy → SignalEvent
                                              ↓
                                         InMemoryBarCache → FactorCalculator
                                              ↓
                                         VirtualAccount → PerformanceReport
```

## 核心组件

### EventReplayer（事件回放器）

从 HistoricalDataProvider 加载历史 BarEvent，按时间顺序发布到 MarketEventBus。

```java
EventReplayer replayer = new EventReplayer(eventBus);
int count = replayer.replay(provider, instrument, timeframe, from, to);
```

### VirtualAccount（虚拟账户）

管理虚拟资金、持仓和交易记录。

```java
VirtualAccount account = new VirtualAccount(BigDecimal.valueOf(10000));
account.openPosition(instrument, OrderSide.LONG, quantity, price, timestamp);
Trade trade = account.closePosition(instrument, exitPrice, timestamp);
```

### PerformanceCalculator（性能计算器）

从交易记录计算各项指标：
- 总收益率、最大回撤、胜率
- 盈亏比、平均盈亏、最大连续亏损

### BacktestEngine（回测引擎）

```java
BacktestResult result = backtestEngine.run(config, strategy, dataProvider);
PerformanceReport report = result.performanceReport();
```

## 回测流程

1. 创建独立的 InMemoryEventBus（不污染全局）
2. 创建独立的 InMemoryBarCache（预加载历史数据）
3. 创建 VirtualAccount（初始资金）
4. 订阅 BarEvent → 策略执行 → 信号收集 → 虚拟交易
5. 回放历史事件
6. 平仓所有持仓
7. 计算性能报告

## 历史数据格式

### InMemoryHistoricalDataProvider

用于测试，直接传入 BarEvent 列表。

### CsvHistoricalDataProvider

从 CSV 文件加载：
```
timestamp,open,high,low,close,volume,quoteVolume
1672515780000,16721.50,16722.00,16721.00,16721.50,100.5,1679231.25
```

## 性能指标

| 指标 | 说明 |
|------|------|
| totalReturn | 总收益率 (%) |
| maxDrawdown | 最大回撤 (%) |
| winRate | 胜率 (%) |
| profitFactor | 盈亏比（总盈利/总亏损） |
| avgWin / avgLoss | 平均盈利/亏损 |
| maxConsecutiveLosses | 最大连续亏损次数 |

## 不能做的事

- 不支持多时间周期回测
- 不支持滑点模拟
- 不支持手续费计算
- 不支持资金费率模拟
- 不支持部分平仓
