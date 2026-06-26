# 持久化设计

> 本文档描述数据持久化的设计和实现。

## 设计理念

- 异步持久化：不影响主流程
- 批量写入：减少数据库压力
- 仅持久化 closed bar：避免频繁写入

## 表结构

### bar_event（K 线数据）

| 字段 | 类型 | 说明 |
|------|------|------|
| exchange | VARCHAR(20) | 交易所 |
| symbol | VARCHAR(20) | 交易对 |
| timeframe | VARCHAR(5) | 时间周期 |
| open_time | BIGINT | K 线开始时间 |
| open/high/low/close_price | DECIMAL(20,8) | OHLC |
| volume | DECIMAL(20,8) | 成交量 |
| quote_volume | DECIMAL(20,8) | 成交额 |

### signal_event（策略信号）

| 字段 | 类型 | 说明 |
|------|------|------|
| strategy_name | VARCHAR(50) | 策略名称 |
| symbol | VARCHAR(20) | 交易对 |
| signal_type | VARCHAR(10) | BUY/SELL/HOLD |
| confidence | DECIMAL(5,4) | 置信度 |
| reason | TEXT | 信号原因 |
| factor_snapshot | JSON | 因子快照 |

### trade_record（交易记录）

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | VARCHAR(20) | 交易对 |
| side | VARCHAR(10) | LONG/SHORT |
| entry/exit_price | DECIMAL(20,8) | 开/平仓价格 |
| realized_pnl | DECIMAL(20,8) | 已实现盈亏 |

## 持久化流程

```
MarketEventBus → EventPersistenceListener → MarketDataPersistenceService → BarEventMapper → MySQL
```

## 不能做的事

- 不支持历史数据回填
- 不支持数据压缩
- 不支持分库分表
