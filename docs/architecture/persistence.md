# 持久化设计

MySQL + MyBatis-Plus 保存标准行情、信号、交易、配置、审计、OMS 和回测研究结果；Flyway 管理 schema。

## 1. 数据域

| 表 | 作用 |
|---|---|
| `bar_event` | finalized K 线，完整市场自然键幂等 |
| `signal_event` | 实时/模拟策略信号与因子快照 |
| `trade_record` | 已关闭交易、费用、净 PnL、策略与订单关联 |
| `raw_message` | 当前主要用于 Coinglass 爆仓原始消息 |
| `admin_config_version` | 配置草稿、校验、发布、归档、回滚 |
| `admin_audit_log` | 管理操作审计 |
| `oms_order` | 订单最新状态快照 |
| `oms_order_event` | 订单生命周期 append-only 事件 |
| `oms_fill` | 逐笔成交 |
| `backtest_run` | 回测指标、市场身份、策略与假设快照 |
| `backtest_trade` | 回测逐笔交易 |
| `backtest_equity_point` | mark-to-market 权益曲线 |
| `backtest_signal` | 回测信号和 point-in-time 因子快照 |
| `instrument_metadata` | 按时间版本化的 tick/step/notional/contract/margin 规则 |
| `paper_account` / `paper_balance` / `paper_position` | 模拟账户、余额与持仓快照 |
| `paper_trade` / `paper_equity_snapshot` | 聚合闭合交易与权益时序 |
| `account_ledger_entry` | 现金、保证金、费用、PnL、funding 的双分录 |
| `event_outbox` | 与业务事实同事务提交的可靠事件 |
| `reconciliation_incident` | 订单、成交、持仓、余额和账本差异 |
| `backtest_job` | 持久化异步实验任务、进度、取消和恢复 |
| `audit_chain_head` | 串行审计 hash chain 的锁定 head |
| `slo_snapshot` | 滚动 SLO/error-budget 的持久快照 |

## 2. 行情写入

实时链路只持久化 closed bar，进入有界内存缓冲，按数量或周期 flush。数据库失败时保留缓冲重试；达到上限时拒绝新增并记录错误。历史回填使用同一自然键 upsert：

```text
UNIQUE(exchange, market_type, symbol, timeframe, open_time)
```

Coinglass K 线的 quote volume 来自 `volume_usd`，base volume 为 0。OKX 永续 base volume 使用 `volCcy`，不是合约张数 `vol`。

## 3. OMS 事务

一个模拟订单变化的 snapshot、lifecycle event、fill、position/balance、double-entry ledger、closed trade/equity 和 outbox 在明确事务中提交。OMS 必需 journal、ledger 或 outbox 写失败采用 fail-closed，不允许账户已经改变而订单记录缺失。

`oms_order_event` 和 `oms_fill` 只追加；`oms_order` 是可更新快照。启动恢复活跃订单和运行中的模拟账户；周期/人工 reconciliation 将差异保存为 incident。实盘超时未知结果进入 `UNKNOWN`，必须查询 venue truth，不能盲目重下单。该能力已经实现代码与模拟盘闭环，但私有交易所凭据认证和多实例 fencing 仍未完成，因此仍不能称为生产级 OMS。

## 4. 回测事务

一次回测结果在一个事务中写入 run、trade、equity point 和 signal。持久化监听器是 required listener，失败时 API 失败。

列表查询只读取 `backtest_run` 摘要；详情按 run ID 加载子表。当前逐行 insert 适合中小型研究窗口，超长回测需要 batch insert、压缩或对象存储 artifact。

## 5. Flyway

- V1-V5：基础行情、配置、审计和用户。
- V6：完整市场身份、K 线自然键、OMS、交易字段加固。
- V7：回测 run/trade。
- V8：策略配置、增强指标、月收益、权益点和信号快照。
- V9：instrument metadata、模拟账户/余额/持仓/mark、双分录、闭合交易、权益与 OMS account 字段。
- V10：outbox、reconciliation incident 和数据集导出 manifest。
- V11：异步回测 job、审计 hash chain 和 SLO snapshot。

V6 会清理同一自然键的旧重复 K 线。所有迁移必须先在隔离 MySQL schema 验证并备份目标库，不能用开发者已有数据库做破坏性试验。

## 6. 当前限制

- Binance/OKX raw payload 未完整归档，canonical event 没有可靠 raw 外键。
- 已有 MySQL outbox、retry/dead-letter 和 lease claim，但没有 broker offset、Kafka 消费者组或跨系统 exactly-once；消费者仍必须幂等。
- 回测已保存 seed、配置、假设和数据版本摘要，尚未封存 Git/container digest 和不可变 raw dataset manifest。
- 没有表分区、冷热分层、Parquet/Object Storage、ClickHouse/Timescale 和保留策略。
- 单 MySQL 是当前一致性中心，尚未完成跨区域复制、PITR 演练、归档/删除策略和大规模容量基准。
- 审计 hash chain 没有外部时间戳/透明日志锚定；具备数据库整体重写权限的攻击者仍可能重算整条链。
