# 交易核心加固记录（2026-07）

## 1. 范围

本轮以实际代码为准，对市场身份、时间一致性、因子、回测、模拟盘、风控、执行、OMS、持久化、配置、安全和 OKX 接入做端到端加固。目标是把“功能存在”推进到“研究结果可解释、不同市场不串数据、订单可审计、失败语义明确”。本轮没有实现交易所私有 API 实盘交易。

## 2. 已解决的高优先级问题

### 2.1 市场身份与 K 线一致性

- 新增 `InstrumentId` 与 `MarketSeriesKey`，缓存、策略状态和查询使用交易所、市场类型、symbol、timeframe 完整键。
- forming K 线与 finalized K 线分离；因子只读取已完成 K 线。
- finalized K 线按 open time 幂等 upsert，重连重复推送不增加样本数。
- `bar_event` 增加完整自然唯一键，历史回填和实时写入使用 upsert。
- 统一 K 线数据库 market type 使用小写持久化 code，避免大小写敏感环境查不到 OKX 数据。
- 数据版本从“行 ID 哈希”改为“完整市场身份 + OHLCV 内容哈希”，同一行被修订后版本会变化。

### 2.2 Point-in-time 与未来函数

- `StrategyContext.at(timestamp)`、`BarCache.getBarsAsOf(...)` 和因子显式 bar slice 保证策略只能看事件时点之前的数据。
- 技术因子不再隐式读取全局最新缓存；衍生因子历史按 instrument 和时间戳隔离。
- `FactorAnalyzer` 使用逐时点前缀数据，而不是用最终缓存回填历史 IC。
- StrategyEngine 按 `strategy + instrument + timeframe` 分区串行，避免同一策略状态并发交叉。

### 2.3 回测真实性与可复现性

- 信号在当前已完成 K 线上生成，最早在下一根 K 线开盘成交；旧 `CLOSE_ONLY` 名称保留兼容，但语义已是 next-bar-open。
- `dataStartTime` 与 `startTime` 分离：预热 K 线更新策略/因子状态，但预热信号不能下单。
- 现货与永续使用不同账户模型；合约在 K 线高低价触及强平价时执行研究级强平。
- 回测结束按最后有效价格平仓，报告基于净 PnL、手续费和权益曲线。
- Sharpe/Sortino 使用日收益并按 365 日年化；月收益和回撤从权益曲线计算。
- 每次运行创建独立风控会话，策略预算等状态不会跨回测污染。
- `StrategyEvaluator` 复用标准 `BacktestEngine`，不再把因子值伪装成价格。

### 2.4 多因子回测与结果持久化

- 新增 `CompositeFactorStrategy` 和可序列化 rule/group/spec，支持单因子、多因子 ALL/ANY/WEIGHTED、常量/价格/因子比较和交叉条件。
- 只有 `BarHistoryFactorCalculator` 可进入当前 K 线历史回测，依赖实时事件状态的因子显式拒绝。
- 新增 `backtest_run`、`backtest_trade`、`backtest_equity_point`、`backtest_signal`，保存配置、假设、完整市场身份、增强指标、月收益、权益、信号因子快照和逐笔交易。
- `/api/admin/backtest-results` 只返回摘要，详情按 run ID 加载；支持 JSON/CSV/Markdown 导出。
- 结果持久化是必需完成监听器；事务写入失败时 API 不得返回成功。
- 管理后台提供因子规则编辑、覆盖率/回填、权益曲线、信号、交易和报告下载。

当前仍未封存数据集修订版本、Git commit、全部 factor/risk/fee/instrument metadata 版本。这些是严格跨机器复现的后续必做项。

### 2.5 风控与账户语义

- `TradingAccount` 统一现货/合约账户接口，并提供 `AccountRiskSnapshot`。
- 现货 SELL 无库存会拒绝，不能凭空开空。
- BUY/SELL 与 LONG/SHORT 分离；反向信号是减仓/平仓，同向信号当前拒绝加仓。
- 风控检查保持无副作用，`StrategyBudgetRule` 仅在成交后记账，平仓时释放预算。
- reduce-only 订单绕过会阻止降风险的暴露限制。
- 每次模拟盘启动使用新账户和新风控会话，但保留全局 Kill Switch 与 OMS。

### 2.6 OMS 与持久化

- 订单的 CREATED、SUBMITTED、ACKNOWLEDGED、PARTIALLY_FILLED、FILLED、REJECTED、CANCEL 状态全部进入订单快照和只追加事件表。
- 每次成交单独写 `oms_fill`；平仓交易写 `trade_record` 并关联策略和订单。
- OMS 是执行必需日志，关键写入失败采用 fail-closed，不再吞掉异常后继续改变账户。
- 详细边界见 `docs/architecture/oms-ems.md`。

### 2.7 数据平台

| 平台 | 实时 | 历史 | 当前数据类型 |
|---|---|---|---|
| Binance | WebSocket | REST | BTC/ETH 现货与 USD-M 永续 K 线 |
| Coinglass | REST 轮询 + WebSocket | REST | BTC/ETH 现货/合约 K 线 + 聚合爆仓订单 |
| OKX | WebSocket | REST | 现货/永续 K 线 |

中央 `MarketUniverseProperties` 只允许三个平台、SPOT/PERPETUAL、BTCUSDT/ETHUSDT，并由全部实时连接器、回填和管理 API 共同校验。Coinglass K 线来自 price-history API，实时性通过已完成 K 线轮询实现，不声称存在 candle WebSocket。

### 2.8 配置与安全

- 配置内容按类型校验 JSON、数值/整数、范围、布尔、数组和 URL，不再把字符串数字静默强转。
- 生产 `pro` profile 检测默认认证 secret 和默认用户密码并拒绝启动。
- 配置同步在事务提交后执行；未知策略配置不会被静默接受。
- OKX 配置进入版本化 CONNECTOR payload，但启停和订阅变更仍需重启。

## 3. 新增管理能力

```text
POST /api/admin/backtests/run
POST /api/admin/backtests/factor-run
GET  /api/admin/backtests/capabilities
GET  /api/admin/backtest-results
GET  /api/admin/backtest-results/{id}
GET  /api/admin/backtest-results/{id}/report?format=json|csv|markdown

GET  /api/admin/orders
GET  /api/admin/orders/{orderId}
GET  /api/admin/fills

POST /api/admin/paper-trading/start
POST /api/admin/paper-trading/stop
GET  /api/admin/paper-trading/status

GET  /api/admin/coverage?exchange=OKX&marketType=PERPETUAL&...
POST /api/admin/backfill?exchange=OKX&marketType=PERPETUAL&...
```

## 4. 数据库迁移

- `V6__create_oms_and_harden_market_schema.sql`：市场表时间字段、完整市场身份、净 PnL、K 线自然唯一键、OMS 三表。
- `V7__create_backtest_run_tables.sql`：回测运行与逐笔交易。
- `V8__extend_backtest_research_reports.sql`：策略快照、增强指标、月收益、权益点和信号快照。

迁移必须在隔离 MySQL schema 先验证，再应用到现有库。V6 会删除同一自然键的旧重复 K 线，只保留 ID 较大的记录；生产执行前应先备份并统计重复量。

## 5. 验证要求

每次修改至少执行：

```powershell
$env:JAVA_HOME='<JDK_17_PATH>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test

cd frontend
npm run lint
npm run build
```

外部 API 测试必须使用 `external` profile，不得让默认测试依赖网络、代理或真实密钥。数据库迁移验证应使用临时 schema，不得对开发者已有 `crypto_trading` 库做破坏性试验。

## 6. 当前生产就绪结论

系统现在是较完整的研究、回测和模拟交易平台基础，不是可直接投入真实资金的交易系统。生产实盘的阻断项是：私有交易适配器、OMS 启动恢复与交易所对账、持久化消息/outbox、真实保证金与资金费率账本、instrument metadata、HA、密钥托管和生产级监控告警。

## 7. 本轮验证结果

- 后端默认离线测试：550 tests，0 failures，0 errors。
- 可执行 JAR：构建成功。
- 前端 lint、TypeScript/Vite 生产构建：通过；Ant Design vendor chunk 仍有体积告警。
- Playwright：6 个认证、因子研究和结果详情关键路径通过。
- 临时隔离 MySQL 8.0.34：Flyway V1-V8 全部成功，JAR health 为 `UP`。
- JAR 连接该隔离 schema 启动成功，Actuator health 为 `UP`。
- 未执行需要真实网络与密钥的 external tests。
