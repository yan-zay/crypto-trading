# CLAUDE.md

This file guides Claude Code and other development agents working in this repository. Actual code and current tests are authoritative; historical completion reports are not.

## Project

实时加密货币市场研究、交易信号、回测和模拟执行系统。系统采用事件驱动架构，统一市场数据、point-in-time 因子、策略、回测、风控、执行、OMS 持久化和管理后台。

当前定位：研究、持久化模拟交易和受保护实盘适配平台。系统已经实现 Binance/OKX 私有 REST、用户事件流、OMS 恢复/对账、模拟账户账本和运维工作台，但**仍不是可直接投入真实资金的生产系统**：私有适配器尚未用项目所有者的交易所测试网/小额实盘凭据完成认证，HA/DR、密钥托管、外部审计锚定和长期故障演练仍是上线阻断项。

## Required Reading

修改对应领域前必须先阅读并同步文档：

- `docs/agentic-quant-trading-roadmap-2026-08.md`：当前权威实施路线图，定义 P0、短中长期目标、Agent/确定性内核边界、晋级门槛和不可由代码宣称完成的外部验收；规划和状态声明必须与其一致。
- `docs/p0-acceptance-runbook.md`：P0 执行命令、故障矩阵、证据要求和当前 NO-GO 验收记录。
- `docs/crypto-trading-system-blueprint.md`：总体蓝图。
- `docs/architecture/trading-core-hardening-2026-07.md`：本轮真实修复、能力与边界。
- `docs/architecture/oms-ems.md`：OMS/EMS 定义、订单持久化和一致性边界。
- `docs/architecture/trading-platform-optimization-2026-07.md`：私有适配、恢复对账、账本、outbox、异步回测、稳健性、成交真实性、审计与 SLO 的实施契约。
- `docs/architecture/okx-market-data.md`：OKX 实时/历史 K 线接入。
- `docs/architecture/market-data-connectors.md`：所有数据平台与连接器规范。
- `docs/architecture/event-model.md`：标准事件与市场身份。
- `docs/architecture/factor-system.md`：因子系统。
- `docs/architecture/strategy-engine-v2.md`：策略分发与状态隔离。
- `docs/architecture/backtest-engine.md`：回测时序、账户、报告和限制。
- `docs/architecture/multi-factor-backtesting.md`：单因子/多因子规则、历史安全和报告。
- `docs/architecture/paper-trading.md`：模拟盘会话。
- `docs/architecture/risk-engine.md`：无副作用风控与会话隔离。
- `docs/architecture/execution-engine.md`：执行语义与失败策略。
- `docs/architecture/persistence.md`：数据库与事务边界。
- `docs/architecture/admin-management-console.md`：管理后台方案。

## Tech Stack

- Java 17 / Spring Boot 3.5.5
- Maven Wrapper 3.9.9（优先使用 `mvnw.cmd` / `./mvnw`；构建强制 JDK 17）
- MyBatis-Plus 3.5.14 / MySQL / Flyway
- OkHttp 4.12.0 HTTP + WebSocket
- TA4J 0.17
- React 19 / Ant Design 6 / ECharts 6 / Vite 8 / TypeScript 6
- JUnit 5 / Mockito / AssertJ / Playwright

## Build And Test

Windows workspace 默认 Java 可能不是 17，运行 Maven 前使用：

```powershell
$env:JAVA_HOME='<JDK_17_PATH>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\mvnw.cmd test
.\mvnw.cmd clean package
.\mvnw.cmd spring-boot:run
```

前端：

```powershell
cd frontend
npm install
npm run lint
npm run build
npm run test:e2e
npm run dev
```

默认测试必须离线且确定性。访问 Binance、Coinglass、OKX 或代理的测试标记 `external`，只通过 external profile 运行。stress/soak 不进入默认测试。

数据库迁移只允许先在临时 schema 验证。不得为了测试 V6/V7 修改或清空开发者现有 `crypto_trading` 数据库。

## Current Architecture

```text
Binance / Coinglass / OKX WS+REST
  -> source normalizer
  -> MarketEventBus (synchronous typed publish/subscribe)
  -> InMemoryBarCache / StrategyEngine / persistence / observability
  -> Strategy.onEvent(point-in-time StrategyContext)
  -> SignalCollector + SignalListener
  -> BacktestEngine / durable BacktestJob worker
  -> Paper brokerage or guarded Binance/OKX private gateway
  -> ExecutionEngine -> RiskEngine -> OMS state machine
  -> fills -> positions/balances -> double-entry ledger -> attribution/TCA
  -> MySQL transaction + outbox -> reconciliation/audit/SLO -> Admin console
```

主要包：

| Package | Responsibility |
|---|---|
| `com.tj.crypto.marketdata` | 事件、normalizer、connector contract、回填、数据质量、OKX mapping |
| `com.tj.crypto.client` | Binance/Coinglass/OKX OkHttp WebSocket clients |
| `com.tj.crypto.event` | `MarketEventBus` |
| `com.tj.crypto.factor` | finalized/as-of 因子、缓存、研究分析 |
| `com.tj.crypto.strategy` | 策略、上下文、信号、管理、评估 |
| `com.tj.crypto.backtest` | 回测、模拟账户、费用、权益与报告 |
| `com.tj.crypto.risk` | 风控规则、会话、仓位、Kill Switch |
| `com.tj.crypto.execution` | 订单语义、状态机、规则、滑点、journal port |
| `com.tj.crypto.storage` | 行情/信号/交易/OMS/回测持久化 |
| `com.tj.crypto.admin` | 认证、RBAC、配置、审计、REST API |
| `frontend` | 管理控制台 |

## Data Platforms

| Platform | Implemented | Not Implemented |
|---|---|---|
| Binance | BTC/ETH SPOT and USD-M PERPETUAL K-line WebSocket + REST history; signed spot/USD-M place/cancel/query/account APIs; spot/futures user streams; write guard | Credentialed testnet/live certification, L2 book/trades and complete funding/OI/mark/index history |
| Coinglass | BTC/ETH SPOT/futures K-line REST history + completed-candle polling; `liquidation_orders` WebSocket | Native candle WebSocket, funding/OI/long-short history and other website indicators |
| OKX | BTC/ETH SPOT/PERPETUAL candle WebSocket + REST history; signed place/cancel/query/account APIs; private order/account/position stream; write guard | Credentialed demo/live certification, dated futures, L2 book/trades and complete funding/OI/mark/index history |

The current product deliberately supports only these three platforms and does not retain placeholders for unimplemented exchanges. The shared market universe is SPOT/PERPETUAL and BTCUSDT/ETHUSDT. Do not broaden connector-local configuration without updating the universe, tests and docs.

OKX is enabled by default. Coinglass K-lines require `COINGLASS_API_KEY`; latest candles use REST polling because the integrated public WebSocket channel is liquidation data, not candles. CONNECTOR config can update values, but startup condition and active subscriptions currently require a supervised restart.

## Non-Negotiable Invariants

### Identity

- A series key is `exchange + marketType + normalized symbol + timeframe`.
- Do not query/cache by symbol alone in new code.
- Persisted K-line market type uses `MarketType.getCode()` (lowercase), matching `BarEventConverter`.

### Time And Research

- Strategies and factors may only consume finalized bars available as of the event timestamp.
- Forming candles never enter factor history.
- Backtest signal on bar N executes no earlier than bar N+1 open.
- Warmup updates state but cannot create orders.
- Never use final/latest cache values to synthesize historical factor observations.

### Orders And Risk

- BUY/SELL and LONG/SHORT are separate concepts.
- Spot cannot open a short from a naked SELL.
- `RiskRule.check` must be pure; state changes only in `onOrderFilled`.
- Stateful risk rules override `newSession`.
- Backtests never write OMS; paper and future live execution must write OMS.
- Required OMS failure is fail-closed. Do not reintroduce catch-and-continue behavior.

### Persistence

- K-lines are natural-key upserts.
- OMS event/fill history is append-only.
- Closed trade PnL is `netPnl = realizedPnl - totalFee`.
- A completed backtest is not successful if required result persistence fails.
- Historical provider HTTP/network/protocol failures must remain visible; never convert them to an empty successful backfill.

## Development Rules

1. Use plan mode before non-trivial implementation.
2. Face documents first: explain design reason, benefits, supported behavior, unsupported behavior, failure semantics and migration impact.
3. Reuse existing interfaces and libraries; do not build a parallel event/factor/order model.
4. Keep modules and files focused; use design patterns only where they remove real complexity.
5. Add unit/integration/regression tests for every behavioral change.
6. Run targeted tests while developing, then all default backend tests plus frontend lint/build.
7. Do not silently weaken correctness to make tests pass. Update stale tests only when the documented contract changed.
8. Do not claim production/live support without private API, reconciliation, recovery and operational evidence.
9. Do not revert unrelated dirty-worktree changes.

## Completed In Current Hardening Round

- Full market identity and finalized/forming bar isolation.
- Point-in-time factors, strategy context and factor analysis.
- Next-bar execution and warmup/trading window separation.
- Equity-curve performance metrics and canonical evaluator backtests.
- Isolated risk sessions for every backtest and paper restart.
- BUY/SELL, LONG/SHORT, spot and reduce-only semantics.
- OMS order snapshot, lifecycle event, fill and trade persistence.
- Fail-closed required OMS journal.
- Backtest run/trade persistence and real Admin result API.
- OKX SPOT/PERPETUAL candle WebSocket and historical REST backfill.
- Binance SPOT/PERPETUAL split WebSocket sessions and historical REST backfill.
- Coinglass SPOT/futures historical K-lines, completed-candle polling and `liquidation_orders` protocol correction.
- Central BTC/ETH market universe enforced by all three connectors, backfill and Admin APIs.
- Configurable single-factor/multi-factor strategies with point-in-time historical-factor gating.
- Full backtest report persistence: strategy config, equity curve, signals, monthly returns and enhanced metrics.
- JSON/CSV/Markdown backtest report export and Admin research/result visualization.
- Type-aware config validation and production default-secret guard.
- Binance/OKX guarded private trading gateways, user-event normalization and `UNKNOWN` order recovery.
- Persistent paper accounts, balances, positions, mark prices, fills, closed trades, equity snapshots and double-entry ledger.
- Market/limit orders, volume-limited partial fills, cancellation, reduce-only close, funding settlement and restart recovery.
- Reconciliation incidents, idempotent repair boundary and transactional outbox with retry/dead-letter operations.
- Durable queued/cancellable backtest jobs, progress, comparison, deterministic seed and auto-backfill contract.
- Bootstrap/statistical evidence grade, reproducibility manifest and execution-capacity/TCA metadata.
- Hash-chained Admin audit records plus chain verification; rolling/persisted SLO and error-budget views.
- Admin Trading Operations, Backtest Jobs and Reliability pages covering the full paper-trading and research workflow.
- Connector duplicate-connect/manual-disconnect race fixes.

See `docs/architecture/trading-core-hardening-2026-07.md` for implementation details.

## Remaining Work By Priority

### P0: Required Before Real Money

| ID | Work | Required outcome |
|---|---|---|
| P0-1 | Exchange certification | Binance/OKX testnet or demo credential contract suite, rate-limit/error corpus, disconnect/resubscribe tests, then capped small-notional canary |
| P0-2 | Production derivatives model | Historical/current margin tiers, cross margin, hedge mode, mark/index/funding truth, ADL/liquidation and fee-tier reconciliation |
| P0-3 | HA and fencing | Single active order writer, database/lease fencing token, duplicate-order prevention across failover and multi-instance worker ownership |
| P0-4 | Security baseline | Vault/KMS/HSM-backed secrets, rotation/revocation drill, key scope/IP whitelist, MFA, dual approval, fine-grained RBAC and supply-chain scanning |
| P0-5 | DR and evidence | Encrypted backups, point-in-time restore, raw-event replay, RPO/RTO drill and independently anchored audit-chain heads |
| P0-6 | Production reliability | 24h/7d soak, exchange/network/database chaos, backpressure, bounded recovery and alert/runbook/on-call acceptance evidence |
| P0-7 | Operational controls | Pre-trade readiness gate, deployment/canary/rollback, disaster kill switch, reconciliation repair approval and incident workflow |

### P1: Research And Operations Quality

| ID | Work | Required outcome |
|---|---|---|
| P1-1 | Data platform breadth | Trades, L2 books, mark/index, funding, OI, long/short ratio and source-specific liquidation history for the three supported platforms |
| P1-2 | Raw data lake/lineage | Binance/OKX/Coinglass raw capture, object storage/Parquet, canonical event linkage, checksums, correction/version policy; local CSV/JSONL export is only the first adapter |
| P1-3 | Research reproducibility | Persist Git commit/container digest and immutable data manifest in addition to the current seed/config/data checksums |
| P1-4 | Robust validation | Purged K-fold + embargo, nested walk-forward, PBO, deflated Sharpe, multiple-testing family correction and Monte Carlo |
| P1-5 | Backtest realism | Historical funding/borrow, dynamic fee/margin tiers, tick/L2 replay, queue position, delisting and survivorship-aware universes |
| P1-6 | Experiment platform scale | Parameter-sweep DAG, quotas/timeouts, distributed lease workers, artifact retention policy and reproducible promotion gates |
| P1-7 | Strategy lifecycle | Draft/review/approve, shadow/canary, promotion/rollback, capital allocation and strategy-level PnL/risk attribution |
| P1-8 | Operations console depth | Incident ownership, approval/repair workflow, connector/data-quality run charts, notification routing and accessibility/responsive hardening |
| P1-9 | Observability depth | Prometheus/Grafana deployment, distributed tracing, burn-rate alerts, cardinality controls and executable runbooks |

### P2: Advanced Capabilities

- Smart order routing, TWAP/VWAP/POV/iceberg and execution venue optimization.
- TCA with arrival/mid/VWAP benchmarks and slippage model calibration.
- Portfolio optimizer, factor exposure, correlation clusters, scenario stress and capital allocator.
- Multi-account/subaccount, multi-currency collateral and portfolio margin.
- ClickHouse/Timescale analytical store, partition lifecycle and online/offline feature parity.
- Notifications, scheduled reports, tax/accounting export and model/strategy governance.

## Loop Candidates

Do not start these loops automatically. They are the highest-value continuous engineering loops after the current task.

| Loop | Scope | Exit condition for one cycle |
|---|---|---|
| LOOP-01 Connector conformance | Binance/Coinglass/OKX payload corpus, reconnect, stale socket, subscription recovery, rate limits | Contract tests pass and 24h canary has no unexplained gap |
| LOOP-02 Point-in-time mutation | Randomized bar revisions, duplicate/out-of-order events, cross-market isolation | Property tests prove no lookahead or identity leakage |
| LOOP-03 Backtest/paper parity | Same strategy/data through backtest replay and paper event replay | Orders, fills and PnL differences are explained within tolerance |
| LOOP-04 OMS state machine | Every event order, duplicate, retry, crash point and unknown state | Model/property tests plus replay reconstruction pass |
| LOOP-05 Reconciliation chaos | Dropped/delayed/duplicated exchange acknowledgements and fills | OMS converges to exchange truth without duplicate orders |
| LOOP-06 Risk invariants | Fuzz positions, prices, leverage, reduce-only and session boundaries | No order can violate declared limits; risk-reducing orders remain possible |
| LOOP-07 Strategy robustness | Walk-forward, regimes, parameter perturbation, PBO/DSR | Promotion report meets predefined statistical gates |
| LOOP-08 Execution calibration | Paper/live shadow fills versus fill/slippage model | Error distributions are stable and versioned by liquidity regime |
| LOOP-09 Data replay/DR | Raw-to-canonical rebuild, DB restore, checksums and corrections | Rebuilt datasets/results match expected versions |
| LOOP-10 Performance soak | Event rate, symbols, strategies, backfills, DB outage and backpressure | SLOs hold without unbounded queues or data loss |
| LOOP-11 Security | Dependency/SAST/secret checks, auth abuse, permission matrix, key rotation | No high severity findings and rotation/revocation drill passes |
| LOOP-12 Admin E2E | Login, config publish/rollback, backtest, full paper order lifecycle, reconciliation, audit/SLO, errors and accessibility | Critical Playwright journeys pass on desktop and mobile targets against isolated MySQL |
| LOOP-13 Documentation drift | Code/API/schema/config versus Markdown and agent files | Generated inventory has no unexplained drift |
| LOOP-14 Ledger/PnL oracle | Random fills, partial close/reversal, fees, funding, mark changes and restart | Ledger balances, snapshots and independently recomputed PnL remain equal to the cent/tick policy |
| LOOP-15 Audit/SLO continuity | Concurrent writes, rollback, restart, retention, chain tampering and SLO window restore | Chain verification detects every mutation and rolling windows retain agreed continuity |

## Agent Execution Protocol

For each future task, the development AI must:

1. Inspect code, tests, schema and the relevant architecture documents.
2. Produce a plan with acceptance criteria, failure semantics and non-goals.
3. Name the existing library/framework used; justify any new dependency before adding it.
4. Update design documentation before or with implementation.
5. Implement in focused modules and preserve compatibility only when it does not hide errors.
6. Add regression, boundary and negative-path tests.
7. Run targeted tests, `mvn test`, frontend `npm run lint`, `npm run build`, and relevant E2E.
8. Report exactly what was verified and what could not be verified.

## Current Verification Snapshot

On 2026-07-15:

- `mvn test`: 574 tests, 0 failures, 0 errors, 0 skipped.
- Frontend `npm run lint` and `npm run build`: passed; the build reports a non-blocking large-chunk warning for Ant Design/ECharts.
- Frontend `npm run test:e2e`: 8 Chromium tests passed, including authentication, multi-factor research, result details, complete paper order lifecycle and audit/SLO views.
- Isolated MySQL 8.0.34 applied Flyway V1-V11 and the Spring Boot application started successfully with public connectors disabled.
- Real HTTP/MySQL paper workflow passed: start account, set mark, market partial fill, cancel remainder, reduce-only close, then query two orders, two fills, one closed trade, zero positions, balances, five equity snapshots and twenty balanced ledger entries.
- After process restart, the same account/order/fill/trade/ledger state remained available; reconciliation reported zero open incidents and audit-chain verification remained valid.
- Deterministic 2,880-bar dataset produced two completed durable backtest jobs, persisted results, research metadata and comparison rows; queued-job cancellation reached `CANCELLED`.
- SLO rolling windows restored from their latest durable snapshots after restart. The HTTP logger was verified not to emit Coinglass query credentials.

The default suite remains offline and deterministic. Binance/OKX private endpoints were not invoked because owner credentials were not supplied; adapter/signature/parser coverage is not a substitute for exchange certification.
