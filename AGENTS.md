# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

实时加密货币交易信号与研究系统。基于事件驱动架构，统一实时行情、因子计算、策略信号、回测、模拟交易、风控、执行、持久化和可观测性。

## Authoritative Roadmap

- 开始非平凡功能、P0、Agent 或实盘相关工作前，必须阅读并遵守 `docs/agentic-quant-trading-roadmap-2026-08.md`。
- P0 实现与状态声明还必须核对 `docs/p0-acceptance-runbook.md` 的故障矩阵和真实环境证据要求。
- 该路线图定义当前 NO-GO 边界、短中长期目标、Agent 与确定性交易内核的权限边界、晋级门槛，以及不能靠代码或 mock 测试宣称完成的外部/时间验收。
- 实现状态以代码、测试和可审计证据为准；路线图中的目标或 checklist 不自动代表已完成。

## Tech Stack

- Java 17 / Spring Boot 3.5.5
- Maven Wrapper 3.9.9（优先使用 `./mvnw` / `mvnw.cmd`，并强制 Java 17+）
- MyBatis-Plus 3.5.14 + MySQL（默认数据库 `crypto_trading`，localhost:3306）
- Flyway（数据库自动迁移）
- OkHttp 4.12.0（HTTP + WebSocket 客户端）
- TA4J 0.17（技术指标）
- Lombok / Hutool / Jackson
- Spring Boot Actuator
- React 19 + Ant Design 6 + ECharts 6 + Vite 8 + TypeScript 6（前端管理控制台）

## Build & Run

```bash
.\mvnw.cmd test                                      # Windows：运行默认测试（需要 JDK 17）
./mvnw test                                          # Linux/macOS：运行默认测试
mvn clean package                                    # 构建
mvn spring-boot:run                                  # 运行（默认 profile: dev，端口 51102）
mvn spring-boot:run -Dspring-boot.run.profiles=pro   # 指定 profile 运行
java -jar target/crypto-trading.jar                  # JAR 运行
```

前端：
```bash
cd frontend
npm install
npm run dev      # 开发服务器
npm run lint     # 静态检查
npm run build    # 生产构建
npm run test:e2e # Playwright 关键路径
```

## Architecture

事件驱动的数据流管线：

```
Binance / Coinglass / OKX WS/REST
  → source Normalizer
  → MarketEventBus (InMemoryEventBus, typed publish/subscribe)
  → InMemoryBarCache / FactorRegistry / StrategyEngine / EventPersistenceListener
  → Strategy.onEvent(StrategyContext)
  → SignalCollector (InMemorySignalCollector)
  → ExecutionEngine / BacktestEngine / PaperTradingEngine
  → OMS / backtest / market-data persistence
```

### 核心包

| 包路径 | 职责 |
|---|---|
| `com.tj.crypto.marketdata` | MarketEvent sealed hierarchy, connector abstractions, normalizers, backfill, data quality |
| `com.tj.crypto.event` | MarketEventBus interface + InMemoryEventBus (typed pub/sub) |
| `com.tj.crypto.client` | OkHttp WebSocket 客户端（Binance + Coinglass + OKX） |
| `com.tj.crypto.service` | WebSocket 生命周期管理 |
| `com.tj.crypto.factor` | 因子计算器（14 个）, registry, bar cache, TA4J converter |
| `com.tj.crypto.strategy` | Strategy interface, context, signal model, StrategyManager, 6 strategies |
| `com.tj.crypto.central` | StrategyEngine (event routing) |
| `com.tj.crypto.backtest` | 回测引擎, paper trading, portfolio backtest, walk-forward optimizer |
| `com.tj.crypto.risk` | 风控引擎, 7 rules, session isolation, kill switch, drawdown guard, position sizer |
| `com.tj.crypto.execution` | 执行引擎（BUY/SELL 与 LONG/SHORT）, order state machine, OMS journal, slippage model |
| `com.tj.crypto.storage` | 行情/信号/交易/OMS/回测持久化, converters/entities/mappers/services |
| `com.tj.crypto.admin` | AdminController, auth, config versioning, audit, overview, REST API |
| `com.tj.crypto.config` | Spring 配置 |
| `com.tj.crypto.common.domain` | 共享领域模型 |

## Configuration

- `application.yml`：主配置（端口 51104，MySQL `crypto_trading`，Flyway 自动迁移）
- `application-dev.yml`：开发配置（端口 51102，SQL debug 日志）
- Spring profiles：dev（默认）、sit、uat、pro
- 环境变量：`DB_USERNAME`、`DB_PASSWORD`、`COINGLASS_API_KEY`、`OKX_ENABLED`、`OKX_INSTRUMENTS`、`PROXY_ENABLED`、`PROXY_HOST`、`PROXY_PORT`

## Conventions

- 策略实现 `Strategy` 接口，注册为 Spring Bean，StrategyManager 自动发现
- 因子实现 `FactorCalculator` 接口，FactorRegistry 自动注册
- 风控规则实现 `RiskRule` 接口，RiskEngine 自动聚合
- 有状态风控规则必须实现 `newSession()`；`check()` 禁止产生副作用
- 新行情查询必须使用 exchange + marketType + symbol + timeframe 完整身份
- 回测不得写 OMS；模拟/未来实盘订单必须写 OMS，必需 journal 失败时 fail-closed
- 市场 universe 当前仅允许 Binance/Coinglass/OKX、SPOT/PERPETUAL、BTCUSDT/ETHUSDT
- 组合历史回测只能使用 `BarHistoryFactorCalculator`，禁止从实时内存合成历史因子
- 完成回测必须持久化 run/trade/equity/signal 和配置/假设快照
- 单表查询使用 LambdaQueryWrapper，复杂 SQL 使用 @Select + #{}
- 不可变值对象使用 record
- DTO 使用 @Data（Lombok）
