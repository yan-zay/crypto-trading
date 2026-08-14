# OMS 与 EMS 架构

## 1. 定义与边界

OMS（Order Management System，订单管理系统）负责订单的事实与生命周期：订单意图、客户端幂等号、状态迁移、逐笔成交、拒绝原因、审计、查询、恢复和对账。

EMS（Execution Management System，执行管理系统）负责如何把订单执行好：交易所路由、价格与数量精度、拆单、限速、重试、撤改单、Maker/Taker 选择、滑点控制和成交质量分析。

二者不是同一个概念。OMS 是订单事实账本，EMS 是执行决策与适配层。当前项目已经具备研究级 OMS 持久化和本地模拟 EMS，但尚不具备任何交易所私有 API 实盘执行能力。

## 2. 当前订单链路

```text
SignalEvent
  -> ExecutionEngine
  -> PositionSizer
  -> RiskEngine
  -> OrderStateMachine
  -> TradingAccount (paper/backtest account)
  -> ExecutionJournal
  -> OmsPersistenceService
       -> oms_order          latest snapshot
       -> oms_order_event    append-only lifecycle
       -> oms_fill           one row per fill
       -> trade_record       closed-position trade
```

订单状态通过 `OrderStateMachine` 驱动，典型路径为：

```text
CREATED -> SUBMITTED -> ACKNOWLEDGED -> PARTIALLY_FILLED -> FILLED
                                  \-> CANCEL_REQUESTED -> CANCELLED
SUBMITTED/ACKNOWLEDGED -----------> REJECTED
```

## 3. 持久化设计

### `oms_order`

保存订单最新快照。主键是内部 `order_id`，`client_order_id` 有唯一约束，用于未来交易所请求幂等。快照包含完整市场身份、策略、BUY/SELL、持仓方向、`reduce_only`、数量、价格、累计成交、状态和各阶段时间。

### `oms_order_event`

只追加状态事件，不覆盖历史。它用于审计、故障分析和未来从事件重建订单状态。当前事件 ID 在本地生成；交易所事件序列和跨进程幂等仍待实盘适配层补充。

### `oms_fill`

保存每次部分成交或完全成交，不能只依赖订单平均成交价。当前模拟成交的流动性角色为 `SIMULATED`，费用字段为零；真实 `exchange_trade_id`、手续费币种和 Maker/Taker 由未来私有交易适配器填充。

### `trade_record`

仓位关闭时保存交易结果，并关联 `strategy_id` 和 `order_id`。`realized_pnl`、`total_fee`、`net_pnl` 分开保存，避免手续费被遗漏。

## 4. 一致性策略

- `OmsPersistenceService` 在单个订单事件内使用数据库事务，同时更新快照、追加事件、追加成交，并在平仓时写交易记录。
- OMS 日志被标记为执行必需日志。`CREATED`、`SUBMITTED` 或 `ACKNOWLEDGED` 写入失败时，执行引擎 fail-closed，不允许账户继续变更。
- 回测订单不写 OMS；每次回测写独立的 `backtest_run` 和 `backtest_trade`，避免污染模拟盘/未来实盘订单账本。
- 每次回测和每次模拟盘启动创建独立风控会话，`StrategyBudgetRule` 等有状态规则不会跨账户泄漏。

仍有一个无法由当前进程内模型消除的窗口：账户或未来交易所已经成交后，`FILLED` 数据库写入可能失败。生产系统必须通过交易所成交回报、持久化消息队列/outbox、启动恢复和定期 reconciliation 修复，不能把本地数据库事务误认为交易所与数据库的分布式事务。

## 5. 管理 API

| API | 作用 |
|---|---|
| `GET /api/admin/orders` | 按交易所、市场、symbol、状态查询订单快照 |
| `GET /api/admin/orders/{orderId}` | 查询订单快照、完整事件和成交 |
| `GET /api/admin/fills` | 查询最近成交 |
| `POST /api/admin/paper-trading/start` | 新建隔离模拟账户与风控会话 |
| `POST /api/admin/paper-trading/stop` | 停止模拟盘 |
| `GET /api/admin/paper-trading/status` | 查询模拟盘状态 |

## 6. 当前 EMS 能力

已支持：

- 信号到订单的 BUY/SELL、LONG/SHORT、`reduceOnly` 语义转换。
- 同向持仓拒绝、反向信号平仓、现货禁止裸卖空。
- 仓位计算、风控短路、Kill Switch、交易所 tick/step/min-notional 对齐。
- 固定滑点、本地立即成交、部分成交状态机 API、模拟账户与手续费模型。

未支持：

- Binance/OKX 私有 REST/WebSocket 签名、下单、撤单、改单和用户数据流。
- 交易所 instrument metadata 自动同步、价格保护、STP、限频与错误码状态映射。
- 多 venue 智能路由、拆单算法、队列位置、真实部分成交、超时与 unknown order 状态。
- 启动恢复、订单/成交/持仓/余额四方对账和人工修复工作台。
- TCA、实现滑点、延迟分解、成交率和 Maker/Taker 质量归因。

## 7. 为什么这样设计

- 订单、事件、成交分表后，既能快速读最新状态，又保留完整审计历史。
- `ExecutionJournal` 是端口，执行领域不依赖 MyBatis；未来可以并行写数据库和事件总线。
- 把回测账本与 OMS 分开，可保持生产订单表语义纯净。
- 明确 fail-closed 与 reconciliation 边界，比吞掉持久化异常更适合资金系统。
