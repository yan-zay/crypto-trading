# 持久化模拟交易

## 1. 目标

模拟交易不是内存里的收益演示，而是实盘前的经纪、OMS、账户与运维验收环境。它复用标准订单语义、`RiskEngine`、instrument metadata、订单状态机和 OMS journal，并把账户、余额、持仓、逐笔成交、闭合交易、权益和双分录账本持久化到 MySQL。

`PaperTradingEngine` 仍可消费 `StrategyEngine` 生成的 `SignalEvent`；管理后台的人工委托走相同的 `PaperOrderService` 和撮合/账本链路。Coinglass 只作为市场数据源，不能成为执行 venue。

## 2. 完整链路

```text
SignalEvent or Admin order ticket
  -> account lifecycle and write eligibility
  -> instrument metadata alignment
  -> pure risk checks
  -> OMS order snapshot + append-only event
  -> mark/volume-aware market or limit matching
  -> zero/partial/full fill
  -> position + balance + fee/funding + double-entry ledger
  -> closed trade + equity snapshot + outbox
  -> attribution/TCA/reporting
  -> reconciliation + audit + SLO
```

市价与限价单均支持。当前成交模型使用 mark 的 high/low、spread、volume 与最大参与率；因此订单可能部分成交，未成交部分可以撤销。合约支持 one-way LONG/SHORT、杠杆、保证金、reduce-only 平仓和 funding settlement。所有参数化成交都必须在报告中标明不是 L2 订单簿回放。

## 3. 持久状态与恢复

核心事实分别保存在：

- `oms_order`、`oms_order_event`、`oms_fill`：订单快照、状态历史和逐笔成交。
- `paper_account`、`paper_balance`、`paper_position`、`paper_mark_price`：账户运行态和资产快照。
- `paper_trade`、`paper_equity_snapshot`：配对后的交易与权益时序。
- `account_ledger_entry`：现金、保证金、手续费、PnL 和 funding 的双分录。
- `event_outbox`：与业务事务一起提交的待发布事件。
- `reconciliation_incident`：无法静默修正的差异与人工处理记录。

服务启动时恢复 `RUNNING` 账户和活跃订单。页面刷新或进程重启不丢失账户、订单、成交、持仓、余额、账本和报表。停止账户会留下最终权益快照；恢复既有账户不会重新初始化余额。

## 4. 事务与恒等式

每次 fill 在一个事务中推进订单累计成交，并同步更新持仓、余额、ledger、closed trade/equity 和 outbox。必要 journal、ledger、outbox 或业务审计失败时操作回滚，接口不能 catch 后继续返回成功。

必须持续满足：

- `order.filledQuantity = sum(fill.fillQuantity)`。
- 终态订单不接受非法状态迁移或重复撤单造成的二次资金变化。
- 每个 `ledgerTransactionId` 的借方和等于贷方和。
- `netPnl = realizedPnl - fees`，权益同时包含未实现 PnL。
- reduce-only 不增加同向敞口；现货裸卖不能创建空头。
- 外部事件、funding event 和 client order ID 使用幂等键防止重复入账。

## 5. 管理 API

```text
POST /api/admin/paper-trading/start
POST /api/admin/paper-trading/stop
POST /api/admin/paper-trading/accounts/{accountId}/resume
GET  /api/admin/paper-trading/accounts
GET  /api/admin/paper-trading/status

POST /api/admin/paper-trading/market-price
GET  /api/admin/paper-trading/marks
POST /api/admin/paper-trading/orders
POST /api/admin/paper-trading/orders/{orderId}/cancel
GET  /api/admin/paper-trading/orders
GET  /api/admin/paper-trading/orders/active

GET  /api/admin/paper-trading/fills
GET  /api/admin/paper-trading/trades
GET  /api/admin/paper-trading/equity
GET  /api/admin/paper-trading/ledger
GET  /api/admin/paper-trading/attribution
GET  /api/admin/paper-trading/execution-quality
POST /api/admin/paper-trading/funding
```

`accountId` 可显式指定；省略时查询服务解析最近活动账户。写接口携带或生成 correlation ID，高风险动作同时写结构化业务审计，外层过滤器再记录 HTTP 结果。

对账与可靠性接口：

```text
POST /api/admin/reconciliation/run
GET  /api/admin/reconciliation/incidents
POST /api/admin/reconciliation/incidents/{incidentId}/resolve
GET  /api/admin/outbox
GET  /api/admin/outbox/backlog
POST /api/admin/outbox/{eventId}/retry
GET  /api/admin/audit
GET  /api/admin/audit/verify
GET  /api/admin/slo/current
GET  /api/admin/slo/history
```

## 6. 管理后台

`Trading Operations` 页面提供账户启动/停止/恢复、mark 注入、订单票据、订单详情与撤单、持仓、余额、fills、closed trades、权益曲线、策略/symbol/side/day 归因、TCA 和账本。`Reliability & Audit` 页面展示 reconciliation、outbox、SLO/error budget 和审计链验证。

页面只是操作与查询入口，不能成为事实源。刷新后所有内容必须从持久 API 重建。

## 7. 已验证与限制

2026-07-15 的隔离 MySQL/真实 HTTP 验收已完成：启动账户、0.02 市价单部分成交 0.01、撤余单、reduce-only 平仓、持仓归零、订单/fill/trade/equity/ledger 查询、无差异对账、审计验证和重启恢复。浏览器完整工作流也由 Playwright 覆盖。

当前限制：

- 仅中央 universe：Binance/Coinglass/OKX、SPOT/PERPETUAL、BTCUSDT/ETHUSDT。
- 合约账本是 USDT 线性 one-way 模型；不支持 cross/portfolio margin、hedge mode、币本位、ADL 和交易所完整清算规则。
- 成交以 K 线/mark/volume 参数模拟，没有真实 L2 队列位置。
- 单数据库部署尚未实现多实例撮合 leader fencing。
- 模拟链通过不代表 Binance/OKX 私有 API 已完成凭据认证，也不能单独作为真实资金上线依据。
