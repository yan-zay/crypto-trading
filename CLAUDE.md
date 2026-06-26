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
  -> normalizer
  -> MarketEventBus
  -> InMemoryBarCache / StrategyEngine / persistence listeners / metrics
  -> Strategy.onEvent()
  -> SignalCollector
  -> ExecutionEngine / BacktestEngine / PaperTradingEngine
```

Key runtime packages:

| Package | Responsibility |
|---|---|
| `com.tj.crypto.marketdata` | Standard market events, connector abstractions, normalizers |
| `com.tj.crypto.event` | `MarketEventBus` and in-process implementation |
| `com.tj.crypto.client` | Coinglass and Binance WebSocket clients |
| `com.tj.crypto.service` | WebSocket lifecycle management |
| `com.tj.crypto.factor` | Factor calculators, registry, bar cache |
| `com.tj.crypto.strategy` | Strategy interface, context, signal model, strategy implementations |
| `com.tj.crypto.central` | Current `StrategyEngine` |
| `com.tj.crypto.backtest` | Historical data replay, virtual account, paper trading, reports |
| `com.tj.crypto.risk` | Risk engine, risk rules, position sizing |
| `com.tj.crypto.execution` | Execution engine, order model, slippage model |
| `com.tj.crypto.storage` | Persistence converters, entities, mappers, services |
| `com.tj.crypto.observability` | Basic metrics |

## Current Runtime Notes

- `CoinglassWebSocketClient` publishes `LiquidationEvent` into `MarketEventBus`; `CgWebSocketService` starts only when `COINGLASS_API_KEY` is configured.
- `BinanceWebSocketClient` can normalize Kline messages into `BarEvent`, but `BinanceWebSocketService` is currently disabled because its lifecycle annotations are commented out.
- `InMemoryEventBus` dispatches events to exact event type subscribers and parent `MarketEvent` subscribers.
- `StrategyEngine` subscribes to `MarketEvent` and asynchronously dispatches matching events to `Strategy` beans.
- `InMemoryBarCache` subscribes to `BarEvent` and feeds technical factor calculators.
- `StrategyProperties`, `FactorProperties`, and `RiskProperties` exist, but verify actual runtime usage before relying on any config item.

## Known Gaps To Respect

- Full tests may currently be red. Do not build new features on a failing baseline without first documenting or fixing the failure.
- Application startup currently depends on MySQL because `AppLifecycleListener` queries `TradeSymbolMapper` on `ApplicationReadyEvent`.
- Backtest and paper trading paths currently need review for parity with `ExecutionEngine`, `RiskEngine`, `PositionSizer`, and `SlippageModel`.
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

### 🔴 高优先级（直接影响系统可用性）

| # | 任务 | 预期产出 | 前置依赖 |
|---|------|---------|---------|
| L1 | 接入 Coinglass 真实爆仓数据，验证 LiquidationEvent 从 WS 到策略引擎的完整链路，修复所有连接和解析问题 | 实时爆仓数据流入系统 | 无 |
| L2 | 用 Binance 历史 K 线数据（BTCUSDT 1min 30天）运行完整回测，验证 MACD 策略的信号质量、盈亏比、最大回撤，修复所有回测问题 | 可信的回测结果 | L3 |
| L3 | 实现 Binance REST API 历史 K 线回填功能，支持按时间范围批量下载并存入 MySQL，用于回测和数据恢复 | 历史数据回填能力 | 无 |

### 🟠 中优先级（提升系统完整性）

| # | 任务 | 预期产出 | 前置依赖 |
|---|------|---------|---------|
| L4 | 添加更多技术指标因子（ATR、ADX、SuperTrend、VWAP）和衍生品因子（多空比、资金费率套利），每个因子配完整单元测试 | 因子库扩展到 15+ | 无 |
| L5 | 实现多交易对并行支持，让系统同时处理 BTCUSDT、ETHUSDT、SOLUSDT 的实时数据和策略信号，修复所有并发问题 | 多交易对并行 | L1 |
| L6 | 实现回测参数优化功能，支持 MACD 周期（fast/slow/signal）网格搜索，输出最优参数组合和对应的性能报告 | 策略参数优化 | L2 |
| L7 | 完善模拟交易引擎，接入实时 Binance 数据，运行 MACD 策略 24 小时模拟交易，输出实时 P&L 和信号日志 | 24h 模拟交易验证 | L5 |

### 🟢 低优先级（长期建设）

| # | 任务 | 预期产出 | 前置依赖 |
|---|------|---------|---------|
| L8 | 实现 Binance 执行适配器（限价单/市价单），接入 testnet，完成下单→成交→对账的完整链路 | 实盘执行能力 | L7 |
| L9 | 添加 Grafana + Prometheus 监控 Dashboard，展示实时 K 线、因子值、策略信号、持仓状态、P&L 曲线 | 可视化监控 | L5 |
| L10 | 实现策略热加载，支持通过 YAML 配置文件动态添加/修改/禁用策略，无需重启应用 | 策略灵活管理 | L5 |

### 建议执行顺序

```
L1 (Coinglass 数据验证) + L3 (历史数据回填) + L4 (因子扩展)  ← 并行
    ↓
L2 (完整回测) + L5 (多交易对)  ← 并行
    ↓
L6 (参数优化) + L7 (24h 模拟)  ← 并行
    ↓
L8 (实盘执行) + L9 (监控) + L10 (热加载)  ← 并行
```
