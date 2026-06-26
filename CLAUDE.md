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

Key runtime packages (133 source files, 43 packages):

| Package | Responsibility |
|---|---|
| `com.tj.crypto.marketdata` | MarketEvent sealed hierarchy, connector abstractions, normalizers, data quality |
| `com.tj.crypto.event` | `MarketEventBus` interface + `InMemoryEventBus` (typed pub/sub) |
| `com.tj.crypto.client` | Coinglass and Binance WebSocket clients (OkHttp + Tyrus) |
| `com.tj.crypto.service` | WebSocket lifecycle management (connect, subscribe, health check) |
| `com.tj.crypto.factor` | Factor calculators (14 factors), registry, bar cache, TA4J converter |
| `com.tj.crypto.strategy` | Strategy interface, context, signal model, 3 strategy implementations |
| `com.tj.crypto.central` | `StrategyEngine` (event routing to strategy beans) |
| `com.tj.crypto.backtest` | Backtest engine, event replayer, virtual account, paper trading, reports |
| `com.tj.crypto.risk` | Risk engine, 3 risk rules, position sizer, risk properties |
| `com.tj.crypto.execution` | Execution engine, order model, fixed slippage model |
| `com.tj.crypto.storage` | Persistence converters, entities, mappers, services, event listener |
| `com.tj.crypto.marketdata.backfill` | Binance historical data provider for backfill |
| `com.tj.crypto.marketdata.quality` | Data quality checker |
| `com.tj.crypto.observability` | Basic metrics |
| `com.tj.crypto.common.domain` | Shared domain models (Exchange, Instrument, Timeframe, etc.) |

## Current Runtime Notes

- `CoinglassWebSocketClient` publishes `LiquidationEvent` into `MarketEventBus`; `CgWebSocketService` starts only when `COINGLASS_API_KEY` is configured.
- `BinanceWebSocketClient` can normalize Kline messages into `BarEvent`, but `BinanceWebSocketService` is currently disabled because its lifecycle annotations are commented out.
- `InMemoryEventBus` dispatches events to exact event type subscribers and parent `MarketEvent` subscribers.
- `StrategyEngine` subscribes to `MarketEvent` and asynchronously dispatches matching events to `Strategy` beans.
- `InMemoryBarCache` subscribes to `BarEvent` and feeds technical factor calculators.
- `StrategyProperties`, `FactorProperties`, and `RiskProperties` exist, but verify actual runtime usage before relying on any config item.

## Known Gaps To Respect

- ~~Full tests may currently be red.~~ **RESOLVED** — 209 tests, all green.
- Application startup currently depends on MySQL because `AppLifecycleListener` queries `TradeSymbolMapper` on `ApplicationReadyEvent`.
- ~~Backtest and paper trading paths currently need review for parity with `ExecutionEngine`, `RiskEngine`, `PositionSizer`, and `SlippageModel`.~~ **RESOLVED** — BacktestEngine integrates ExecutionEngine, RiskEngine, PositionSizer, and SlippageModel. Full backtest verification tests pass.
- Account equity, margin, short position semantics, fee model, min notional, precision, funding fee, and liquidation rules are not yet production-grade.
- `TestController` is a development-only controller and must not be exposed in production.

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

### ✅ 已完成任务 (L1-L9)

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

### 🔴 待办任务

| # | 任务 | 预期产出 | 前置依赖 |
|---|------|---------|---------|
| L10 | 实现策略热加载，支持通过 YAML 配置文件动态添加/修改/禁用策略，无需重启应用 | 策略灵活管理 | L5 |
