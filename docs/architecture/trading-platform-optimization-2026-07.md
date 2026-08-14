# 交易平台优化实施方案（2026-07）

## 1. 目标与安全边界

本轮把系统从“研究 + 进程内模拟成交”推进到“可恢复、可对账、可审计的模拟交易闭环”，并为 Binance/OKX 私有交易适配保留稳定端口。交付顺序遵循资金安全优先：订单事实与账户账本先于高级研究功能，可靠事件先于异步扩展，审计和 SLO 贯穿全链路。

本轮必须贯通：

```text
策略信号或人工委托
  -> 交易品种规则校验
  -> 风控与订单受理
  -> OMS 状态机
  -> 模拟撮合（市价/限价/部分成交/撤单）
  -> 逐笔成交
  -> 双分录账户账本
  -> 余额/持仓/已实现与未实现 PnL
  -> 交易聚合与策略归因
  -> 管理 API 与可视化报表
  -> 审计、SLO、恢复与对账
```

安全边界：

- 实盘写操作默认关闭。没有显式启用、凭据、时钟校验、metadata 同步和 reconciliation 健康状态时，不允许向交易所发送委托。
- Coinglass 是数据源，不是执行 venue；模拟盘可按其市场身份研究，但实盘路由只允许 Binance/OKX。
- 回测、模拟盘、未来实盘使用相同订单语义，但账本和运行标识隔离。
- 本轮不实施第 8 项中的 HA、DR、KMS/Vault、MFA 和供应链平台，只实施防篡改审计链与 SLO。

## 2. 八项能力设计

### 2.1 私有交易 API 与用户事件流

新增 `TradingVenueGateway` 端口，统一 place/cancel/query/open-orders/account-snapshot，以及标准化的订单、成交、余额、持仓事件。模拟实现作为可执行参考；Binance/OKX 适配器负责签名、错误映射和原始用户事件标准化，默认禁用真实写操作。

优点：策略、OMS 和管理端不依赖交易所 payload；模拟与实盘可以运行同一组契约测试。限制：没有真实 API key 的测试环境只能验证签名向量、请求结构和 payload 归一化，不能声称完成交易所沙盒认证。

### 2.2 OMS 恢复与 reconciliation

数据库中的订单快照、只追加事件和逐笔成交是订单事实源。启动时恢复活跃订单；定时比较订单累计成交与 fill、持仓与账本、余额与分录，并把差异写入 reconciliation incident。修复动作必须幂等、可审计，不能静默覆盖事实。

失败语义：未知交易所结果进入 `UNKNOWN`/待对账状态；重复事件通过外部事件 ID 或幂等键去重；必要 journal/outbox 写失败时整笔数据库事务回滚。

### 2.3 Instrument metadata 与衍生品账本

`instrument_metadata` 按 exchange + market + symbol + 生效时间版本化，保存 tick/step/min/max quantity、min notional、contract multiplier、状态、结算币、费率、杠杆和维持保证金参数。所有委托必须读取 metadata，不再使用硬编码常量。

模拟账户使用持久化双分录：可用现金、冻结保证金、手续费、资金费率、已实现 PnL 等科目借贷恒等。仓位快照保存方向、数量、均价、mark、保证金、杠杆、已实现/未实现 PnL。第一版支持 one-way position、USDT 线性合约和现货；复杂组合保证金和币本位合约暂不支持。

### 2.4 Durable event/outbox 与研究数据导出

订单、成交、账本和 reconciliation 在业务事务内同时写 `event_outbox`。发布器采用 claim/lease、重试、退避和 dead-letter 状态；消费者用 event ID 幂等。当前部署先使用 MySQL outbox，不提前引入 Kafka。

数据导出保存 manifest、查询范围、数据版本、校验和和行数，可导出 canonical CSV/JSONL。对象存储/Parquet 是后续适配层，本轮不绑定具体云平台。

### 2.5 异步回测任务与比较

回测请求先持久化为 job，再由有界线程池 claim 执行。状态包括 QUEUED/RUNNING/COMPLETED/FAILED/CANCEL_REQUESTED/CANCELLED；保存进度、错误、请求快照、结果 ID 和时间。管理端支持取消、轮询、按多个结果比较核心指标。

同步 API 暂时保留兼容，但新界面默认走异步任务。取消是协作式取消：数据装载前、每个 bar 批次和持久化前检查取消标志。

### 2.6 统计稳健性

每个回测报告增加样本量质量、买入持有基准、超额收益、bootstrap 收益/Sharpe 置信区间、概率 Sharpe、最小样本提示和多重试验修正后的显著性提示。随机过程必须保存 seed。

这些指标用于揭示不确定性，不把统计显著性等同于未来盈利。完整 purged K-fold、nested walk-forward、PBO/DSR 参数族分析需要实验组上下文，作为异步 sweep 的后续扩展。

### 2.7 真实成交、冲击与容量

统一 `ExecutionCostModel` 输入 side、订单类型、数量、bar/quote、延迟和市场容量，输出成交切片、spread、slippage、impact、未成交量和流动性角色。默认模型支持：

- next-bar 延迟；
- bid/ask spread；
- 基于参与率和平方根模型的市场冲击；
- 单 bar 最大参与率导致的部分成交；
- 限价触达与 maker/taker 语义；
- 容量利用率和成本占毛收益指标。

没有 L2 数据时结果是透明的参数化估计，报告必须展示假设，不能标记为真实订单簿回放。

### 2.8 审计与 SLO（本轮第 8 项唯一范围）

所有写 API、自动委托、撤单、reconciliation 修复和配置操作写结构化审计记录，包含 request/correlation ID、操作者、资源、结果、延迟、来源 IP、前后摘要和 hash chain。提供链校验 API。hash chain 能检测数据库内的删除/修改，但若数据库管理员可重写整条链则不能提供密码学意义的不可抵赖；外部时间戳锚定留待后续。

SLO 首批覆盖：模拟订单受理成功率、订单在阈值内完成比例、reconciliation 无差异比例、outbox 投递、行情新鲜度、回测任务完成率和审计追加成功率。管理端展示目标、实际值、样本、平均/最大延迟、状态和剩余 error budget；Micrometer histogram 提供延迟分位数。outbox backlog/最老事件作为独立运维指标展示，不冒充当前 SLO 样本。

## 3. 模拟交易数据模型

核心表：

| 表 | 作用 |
|---|---|
| `paper_account` | 模拟账户生命周期和基准币种 |
| `paper_balance` | 资产余额、可用与冻结金额快照 |
| `paper_position` | 持仓、mark、保证金与 PnL 快照 |
| `account_ledger_entry` | 双分录分录；同一 transaction 借贷和必须相等 |
| `paper_trade` | 配对后的完整开平仓交易，用于归因 |
| `paper_equity_snapshot` | 权益、余额、保证金和未实现 PnL 时序 |
| `reconciliation_incident` | 对账差异、状态、修复动作 |
| `event_outbox` | 事务 outbox 与投递状态 |

OMS 表增加 `account_id`、`venue_order_id`、版本、来源和外部事件幂等字段。任何余额变化必须有 ledger transaction；任何 fill 必须同时更新订单累计成交、持仓/余额、分录和 outbox。

## 4. API 与界面验收

管理 API 至少提供：

- 模拟账户 start/stop/resume/status；
- place market/limit、cancel、订单详情、活跃订单；
- balances、positions、fills、closed trades、equity curve；
- 绩效概览、按策略/symbol/side/day 归因、执行质量；
- reconciliation run/list/resolve；
- backtest job submit/status/cancel/list/compare；
- audit search/verify、SLO current/history；
- dataset export manifest/download。

界面至少包含订单票据、订单与成交、持仓、余额、权益/PnL、归因、执行质量、对账、审计、SLO 和异步回测任务。危险动作需要明确确认和成功/失败反馈。

## 5. 测试与完成标准

1. 单元测试覆盖状态机边界、签名、metadata 对齐、撮合、部分成交、双分录恒等、PnL、资金费率、统计指标和 SLO 计算。
2. MySQL 集成测试验证 Flyway、事务回滚、幂等、重启恢复、outbox claim 和 reconciliation。
3. 端到端测试必须实际完成：启动模拟盘 -> 下限价单 -> 部分成交 -> 撤余单 -> 下市价单平仓 -> 查询订单/成交/持仓/余额 -> 查看归因/权益报表。
4. 故障测试覆盖重复成交、journal/outbox 写失败、进程重启、取消与成交竞态、异步任务失败/取消。
5. 每轮执行 `mvn test`、前端 lint/build、Playwright，并在隔离 MySQL 上运行迁移和应用健康检查。

完成标准不是“接口存在”，而是数据恒等式成立：

- `order.filled_quantity = sum(fill.quantity)`；
- 终态订单不可非法迁移；
- 每个 ledger transaction 借方和 = 贷方和；
- 余额/持仓可从账本与成交重建；
- 重启后活跃订单和账户状态不丢失；
- 报表聚合可追溯到 fill、order、signal/strategy 和 ledger transaction。

## 6. 实施状态（2026-07-15）

| 优先级 | 状态 | 已交付能力 | 仍然存在的边界 |
|---|---|---|---|
| P1 私有交易 | 已完成代码 | Binance spot/USD-M、OKX spot/swap 签名 REST，订单/账户查询，私有事件归一化，写保护和 `UNKNOWN` 恢复入口 | 未使用所有者凭据完成交易所 demo/testnet 与小额实盘认证 |
| P2 OMS/对账 | 已完成 | 活跃订单恢复，订单/fill/position/balance/ledger 对账，incident 与人工解决，幂等状态推进 | 实盘 venue truth 仍需凭据环境和长期断线故障验证 |
| P3 metadata/账本 | 已完成 | 版本化交易规则，持久模拟账户、余额、one-way 持仓、成交、已关闭交易、权益、双分录和 funding | 不支持组合保证金、币本位、hedge mode 和完整 margin tier |
| P4 outbox/数据集 | 已完成 | MySQL outbox、claim/lease、重试/dead-letter、backlog；CSV/JSONL manifest/checksum 导出 | 未接 Kafka、对象存储和 Parquet，尚无跨区域复制 |
| P5 异步回测 | 已完成 | 持久 job、受限 worker、进度、取消、崩溃恢复、结果比较、可选 auto-backfill | 单节点 worker；没有 sweep DAG、租户配额和分布式租约 |
| P6 稳健性 | 已完成基础层 | bootstrap 区间、均值为正概率、概率 Sharpe、最小记录长度、证据等级、seed/数据/配置摘要 | purged K-fold、PBO、deflated Sharpe 和完整试验族校正未实现 |
| P7 成交真实性 | 已完成参数模型 | spread、参与率、平方根冲击、部分成交、maker/taker、TCA 与容量指标 | 没有 L2 队列回放，结果仍是可解释估算 |
| P8 审计/SLO | 已按限定范围完成 | 所有 Admin 写请求审计、关键写事务内业务审计、SHA-256 hash chain/verify、7 项滚动 SLO、持久快照与重启恢复 | 无外部 hash 锚定、MFA/KMS/HA/DR，本轮按要求不开发 |

## 7. 模拟交易闭环验收证据

在隔离 MySQL 8.0.34 与实际 Spring Boot HTTP API 上完成以下链路，而不是只依赖 mock：

1. 创建并启动 10,000 USDT 模拟账户，写入 BTCUSDT 永续 mark/volume。
2. 提交 0.02 BTC 市价买单；容量约束只成交 0.01，OMS 状态为 `PARTIALLY_FILLED`。
3. 撤销剩余 0.01，订单终态为 `CANCELLED`。
4. 更新 mark 后提交 0.01 BTC reduce-only 卖单，订单 `FILLED`，持仓归零。
5. 查询得到 2 个订单、2 个 fill、1 个闭合 trade、0 个持仓、5 个权益快照和 20 条 ledger entry；6 个 ledger transaction 分别满足借贷平衡。
6. 运行 reconciliation 后无未解决 incident；outbox 无 pending；审计链校验有效。
7. 重启应用后账户、订单、fill、trade、ledger 和报表仍可查询，SLO 窗口从持久快照恢复。

浏览器 Playwright 用例另外覆盖同一管理工作流的订单票据、撤单、持仓/余额、成交、权益曲线、归因、TCA、账本和对账可视化。它验证前端交互契约，但数据库闭环以上述实际 HTTP/MySQL 验收为准。

## 8. 设计收益与不能保证的事项

设计收益：

- OMS 是订单事实源，账户账本是资金事实源，页面不从临时内存拼装关键状态。
- 下单、状态推进、fill、持仓、余额、ledger、outbox 与关键审计位于明确事务边界，失败时不会返回伪成功。
- request/correlation/strategy/account/order/fill/ledger ID 让交易后聚合可以回溯到原始执行事实。
- 回测任务和模拟盘都可重启恢复，研究结果同时保存统计证据和假设限制。

不能据此声称：

- “任何环境绝无问题”。当前证据覆盖代码、确定性测试和隔离 MySQL；真实交易所限流、账户模式、地区网络和凭据权限仍可能暴露新问题。
- 审计记录不可由拥有数据库和应用密钥的管理员整体重写。当前链只能检测普通数据库内修改/删除，外部锚定以后再做。
- 参数化 K 线成交等价于真实订单簿。生产资金上线前必须完成 L2/真实 fill 校准和小额 canary。

## 9. 后续优先级与 Loop

下一阶段先做真实资金阻断项：交易所 demo/testnet 认证矩阵、cross/hedge/margin-tier 真值、单写者 fencing、Vault/KMS/MFA/双人审批、备份恢复和 24h/7d chaos soak。研究侧随后补 L2/trades/funding/OI 数据湖、purged/nested 验证、分布式 sweep 和策略晋级治理。

本轮不执行 Loop。待执行 Loop 的完整清单、每轮退出条件和验证命令维护在 `CLAUDE.md` 的 `Loop Candidates`，其中最先执行的应是 connector conformance、OMS/reconciliation chaos、ledger/PnL oracle、backtest-paper parity、performance soak 和 audit/SLO continuity。
