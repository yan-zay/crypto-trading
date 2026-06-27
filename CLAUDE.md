# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

实时加密货币交易信号与研究系统。当前代码已经从早期 `DataCenter + BaseStrategy` 结构迁移到基于 `MarketEvent` 的事件驱动架构，目标是统一实时行情、因子计算、策略信号、回测、模拟交易、风控、执行、持久化和可观测性。

开发时以实际代码为准，不要再按旧的 `DataCenter`、`BaseStrategy`、`Symbol/Indicator` 枚举方案继续扩展。

## Required Reading

- `docs/crypto-trading-system-blueprint.md`：交易系统总体蓝图。
- `docs/architecture/admin-management-console.md`：管理后台规划方案，后续后台开发必须优先遵守。
- `docs/architecture/event-model.md`：标准市场事件模型。
- `docs/architecture/market-data-connectors.md`：市场数据接入设计。
- `docs/architecture/factor-system.md`：因子系统设计。
- `docs/architecture/strategy-engine-v2.md`：策略引擎设计。
- `docs/architecture/backtest-engine.md`：回测设计。
- `docs/architecture/paper-trading.md`：模拟交易设计。
- `docs/architecture/risk-engine.md`：风控设计。
- `docs/architecture/execution-engine.md`：执行设计。
- `docs/architecture/persistence.md`：持久化设计。
- `docs/architecture/observability.md`：可观测性设计。

## Tech Stack

- Java 17 / Spring Boot 3.5.5
- Maven（无 Maven Wrapper，需本地安装 `mvn`）
- MyBatis-Plus 3.5.14 + MySQL（默认数据库 `id_generator`，localhost:3306）
- OkHttp 4.12.0（HTTP 客户端）
- Tyrus 2.1.3（Jakarta WebSocket 客户端）
- TA4J 0.17（技术指标）
- Lombok / Hutool / Jackson
- Spring Boot Actuator
- React 19 + Ant Design 6 + Vite 8 + TypeScript 6（前端管理控制台）

## Build & Run

```bash
mvn test
mvn clean package
mvn spring-boot:run
mvn spring-boot:run -Dspring-boot.run.profiles=pro
java -jar target/crypto-trading-1.0.0.jar
```

On this Windows workspace, use JDK 17 when running Maven:

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test
```

Frontend (admin console):

```bash
cd frontend
npm install
npm run dev      # 开发服务器
npm run build    # 生产构建 → frontend/dist/
```

## Current Architecture

```text
Market data WS/REST
  -> Normalizer (BinanceKlineNormalizer, CoinglassLiquidationNormalizer)
  -> MarketEventBus (InMemoryEventBus, typed publish/subscribe)
  -> InMemoryBarCache / FactorRegistry / StrategyEngine / EventPersistenceListener
  -> Strategy.onEvent(StrategyContext)
  -> SignalCollector (InMemorySignalCollector)
  -> ExecutionEngine / BacktestEngine / PaperTradingEngine
  -> Persistence (BarEvent/SignalEvent/TradeRecord via converter+mapper)
```

Key runtime packages (202 source files, 25+ packages):

| Package | Responsibility |
|---|---|
| `com.tj.crypto.marketdata` | MarketEvent sealed hierarchy, connector abstractions, normalizers, backfill, data quality |
| `com.tj.crypto.event` | `MarketEventBus` interface + `InMemoryEventBus` (typed pub/sub) |
| `com.tj.crypto.client` | Coinglass and Binance WebSocket clients (OkHttp + Tyrus, 4 clients) |
| `com.tj.crypto.service` | WebSocket lifecycle management (connect, subscribe, health check, 4 services) |
| `com.tj.crypto.factor` | Factor calculators (14 calculators: 9 technical + 5 derivative + 7 framework/analysis classes), registry, bar cache, TA4J converter |
| `com.tj.crypto.strategy` | Strategy interface, context, signal model, StrategyManager (hot-reload), 6 strategy implementations, portfolio, evaluator |
| `com.tj.crypto.central` | `StrategyEngine` (event routing to strategy beans) |
| `com.tj.crypto.backtest` | Backtest engine, event replayer, virtual account, paper trading, portfolio backtest, walk-forward optimizer, parameter optimizer, fee model, futures account, reports |
| `com.tj.crypto.risk` | Risk engine, 6 risk rules (MaxDailyLoss/MaxLossPerTrade/MaxPositionSize/Cooldown/PerSymbolExposure/TotalExposure), kill switch, drawdown guard, position sizer |
| `com.tj.crypto.execution` | Execution engine, order state machine, order model, fixed slippage model |
| `com.tj.crypto.storage` | Persistence converters (3), entities (4), mappers (4), services (8: bar/signal/trade/raw/coverage/lineage/autobackfill/market), event listener |
| `com.tj.crypto.admin` | AdminController, auth interceptor, config versioning (ConfigVersionService), audit service, overview service, REST API for system status/strategies/factors/signals/health |
| `com.tj.crypto.config` | Spring configuration: OkHttp proxy, thread pool, WebSocket container, dotenv, properties |
| `com.tj.crypto.common.domain` | Shared domain models (Exchange, Instrument, Timeframe, OrderSide, ChannelType, MarketType, MarketRegime) |
| `com.tj.crypto.observability` | System metrics, alert service, alert rules, metrics snapshot |
| `com.tj.crypto.pojo.dto` | Legacy DTOs (CgResultDTO, KLineData, LiquidationOrder, LiquidationOrderSum) |
| `com.tj.crypto.entity` | Database entities and base classes (PhysicsTimeBaseDO, TradeSymbolDO) |
| `com.tj.crypto.mapper` | MyBatis-Plus Mappers (BaseMapperX, TradeSymbolMapper) |
| `com.tj.crypto.listener` | AppLifecycleListener (startup initialization) |
| `com.tj.crypto.task` | Scheduled tasks (CgTask) |

## Current Runtime Notes

- `CoinglassWebSocketClient` publishes `LiquidationEvent` into `MarketEventBus`; `CgWebSocketService` starts only when `COINGLASS_API_KEY` is configured.
- `BinanceWebSocketClient` can normalize Kline messages into `BarEvent`, but `BinanceWebSocketService` is currently disabled because its lifecycle annotations are commented out.
- `InMemoryEventBus` dispatches events to exact event type subscribers and parent `MarketEvent` subscribers.
- `StrategyEngine` subscribes to `MarketEvent` and asynchronously dispatches matching events to `Strategy` beans.
- `InMemoryBarCache` subscribes to `BarEvent` and feeds technical factor calculators.
- `StrategyProperties`, `FactorProperties`, and `RiskProperties` exist, but verify actual runtime usage before relying on any config item.

## Known Gaps To Respect

- ~~Full tests may currently be red.~~ **RESOLVED** — 520 tests across 70 test classes, all green (1 skipped).
- Application startup currently depends on MySQL because `AppLifecycleListener` queries `TradeSymbolMapper` on `ApplicationReadyEvent`.
- ~~Backtest and paper trading paths currently need review for parity with `ExecutionEngine`, `RiskEngine`, `PositionSizer`, and `SlippageModel`.~~ **RESOLVED** — BacktestEngine integrates ExecutionEngine, RiskEngine, PositionSizer, and SlippageModel. Full backtest verification tests pass.
- Account equity, margin, short position semantics, fee model, min notional, precision, funding fee, and liquidation rules are not yet production-grade.
- `TestController` is a development-only controller and must not be exposed in production.
- Frontend chunk size warning (>500 kB) — consider code-splitting for production.
- Old Tyrus-based WebSocket clients (`BinanceWebSocketClient`, `CoinglassWebSocketClient`) coexist with OkHttp variants — old clients should be removed when confirmed unused.
- `TradeSymbolDO` class name is misleading (maps to ID generator table `biz_tiny_id`).
- `KLineData` DTO is still mutable (should be converted to record).

## Development Rules

- Use plan mode before implementation when adding or changing non-trivial functionality.
- Face documents first. For every new feature or behavioral change, update the relevant Markdown document with design reason, benefits, supported behavior, and unsupported behavior.
- Keep code modular. Do not pile unrelated logic into one large class.
- Prefer existing libraries and local patterns over custom rewrites.
- Add or update tests for each change.
- Run `mvn test` after every change. For startup-sensitive changes, also run `mvn -DskipTests package` and start the jar or `spring-boot:run`.
- Do not revert user changes unless explicitly requested.

## Admin Console Guidance

Management backend and UI work must follow `docs/architecture/admin-management-console.md`.

High-level direction:

- Add admin backend code under `com.tj.crypto.admin`.
- Start with read-only operational views and configuration inventory.
- Add versioned config, validation, publish, rollback, and audit before enabling live trading controls.
- UI should be a dense trading operations console, not a landing page.
- Do not expose API keys or secrets in frontend responses.
- Do not add live trading execution controls until risk, execution, audit, permission, and kill switch flows are mature.

## Loop 开发任务清单

以下是按优先级排列的持续开发迭代任务。每项任务适合用 `/loop` 或 Workflow 驱动完成。

### ✅ 已完成任务 (L1-L10)

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| L1 | 接入 Coinglass 真实爆仓数据 | ✅ 完成 | CoinglassWebSocketClient → LiquidationNormalizer → LiquidationEvent → MarketEventBus 链路已接通 |
| L2 | 完整回测验证 | ✅ 完成 | BacktestEngine 支持历史数据回放，FullBacktestTest 和 BacktestVerificationTest 通过 |
| L3 | Binance 历史 K 线回填 | ✅ 完成 | BinanceHistoricalDataProvider 支持按时间范围批量下载，BinanceHistoricalDataProviderTest 通过 |
| L4 | 因子库扩展到 14 | ✅ 完成 | 9 技术因子（SMA/EMA/MACD/RSI/ATR/ADX/SuperTrend/VWAP/BollingerBand）+ 5 衍生品因子 |
| L5 | 多交易对并行 | ✅ 完成 | MultiPairIntegrationTest 验证 BTCUSDT/ETHUSDT/SOLUSDT 并行处理 |
| L6 | 参数优化 | ✅ 完成 | ParameterOptimizationTest 验证 MACD 周期网格搜索 |
| L7 | 模拟交易引擎 | ✅ 完成 | PaperTradingEngine + VirtualAccount，PortfolioBacktestTest 通过 |
| L8 | 执行引擎 | ✅ 完成 | ExecutionEngine + FixedSlippageModel，ExecutionEngineTest 通过 |
| L9 | 可观测性基础 | ✅ 完成 | observability 包基础指标，DataQualityChecker 数据质量检查 |
| L10 | 策略热加载 | ✅ 完成 | StrategyManager 支持运行时启用/禁用策略，AdminController 提供 REST API，StrategyHotReloadIntegration 验证 |

### ✅ 已完成任务 (L11-L18)

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| L11 | Admin 管理 API | ✅ 完成 | AdminController 提供 /status、/signals、/factors、/strategies、/health 端点，AdminControllerTest 通过 |
| L12 | 风控引擎 | ✅ 完成 | RiskEngine + 3 条规则（MaxDailyLoss/MaxLossPerTrade/MaxPositionSize），RiskEngineTest 通过 |
| L13 | 仓位管理 | ✅ 完成 | PositionSizer 计算仓位大小，ExecutionEngine 集成风控+仓位+滑点 |
| L14 | 持久化层 | ✅ 完成 | EventPersistenceListener + BarEvent/SignalEvent/TradeRecord 转换器+Mapper+Service |
| L15 | 策略扩展到 6 个 | ✅ 完成 | LiquidationSpike/MacdCross/RsiCross/SuperTrend/BollingerBreakout/AtrTrailingStop |
| L16 | 组合回测引擎 | ✅ 完成 | PortfolioBacktestEngine 支持多策略独立回测+合并报告，FourStrategyPortfolioTest 通过 |
| L17 | Walk-Forward 优化 | ✅ 完成 | WalkForwardOptimizer 支持滚动窗口参数优化，WalkForwardTest 通过 |
| L18 | 最终集成验证 | ✅ 完成 | FinalIntegrationTest 覆盖数据管线、回测管线、Admin API、策略热加载、4 策略组合回测 |

### ✅ 已完成任务 (L19-L35)

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| L19 | 风控规则扩展 | ✅ 完成 | 新增 CooldownRule、PerSymbolExposureRule、TotalExposureRule，共 6 条风控规则 |
| L20 | Kill Switch / Drawdown Guard | ✅ 完成 | KillSwitch 紧急停止、DrawdownGuard 回撤保护，RiskEngineTest/KillSwitchTest/DrawdownGuardTest 通过 |
| L21 | 期货账户模型 | ✅ 完成 | FuturesAccount + FuturesPosition + MarginMode，FuturesAccountTest 通过 |
| L22 | 订单状态机 | ✅ 完成 | OrderStateMachine 管理订单生命周期，OrderStateMachineTest 通过 |
| L23 | 回测假设文档 | ✅ 完成 | BacktestAssumptions 明确回测简化假设，BacktestAssumptionsTest 通过 |
| L24 | Admin 认证与鉴权 | ✅ 完成 | AuthService + AuthInterceptor + Role 枚举，AuthServiceTest/AuthInterceptorTest 通过 |
| L25 | 配置版本管理 | ✅ 完成 | ConfigVersionService + ConfigVersionDO + ConfigSyncService，ConfigVersionServiceTest 通过 |
| L26 | 审计日志 | ✅ 完成 | AuditService + AuditLogDO + AuditLogMapper |
| L27 | Admin 概览服务 | ✅ 完成 | AdminOverviewService 聚合系统状态，AdminOverviewServiceTest 通过 |
| L28 | 数据覆盖率服务 | ✅ 完成 | DataCoverageService 检测数据缺口，CoverageReport 生成覆盖率报告，DataCoverageServiceTest 通过 |
| L29 | 数据血缘追踪 | ✅ 完成 | DataLineageService 追踪数据来源和版本，DataLineageServiceTest 通过 |
| L30 | 原始消息持久化 | ✅ 完成 | RawMessagePersistenceService + RawMessageDO + RawMessageMapper，RawMessagePersistenceServiceTest 通过 |
| L31 | 市场数据自动回填 | ✅ 完成 | AutoBackfillService + MarketDataPersistenceService |
| L32 | 告警系统 | ✅ 完成 | AlertService + AlertRule + AlertEvent，AlertServiceTest 通过 |
| L33 | 因子分析框架 | ✅ 完成 | FactorAnalyzer + FactorReturnStats，FactorAnalyzerTest 通过 |
| L34 | 压力测试 | ✅ 完成 | StressTest 覆盖大数据量、并发策略、异常数据场景 |
| L35 | 前端管理控制台 | ✅ 完成 | React + Ant Design + Vite 前端，Dashboard/Factors/Signals/Strategies/Risk/Backtests 6 个页面 |
