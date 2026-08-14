# 加密货币交易系统 — 架构蓝图

> 本文档是 `crypto-trading` 项目的总体开发指导文档。所有开发工作必须参照本文档的阶段目标、架构约束和设计原则执行。
>
> 最后更新：2026-06-27（最终文档同步 + 代码清理 + 验证 + 项目状态同步）

---

## 目录

1. [系统愿景](#1-系统愿景)
2. [技术栈](#2-技术栈)
3. [架构演进路线](#3-架构演进路线)
4. [目标架构总览](#4-目标架构总览)
5. [第一阶段：事件模型与数据管线](#5-第一阶段事件模型与数据管线)
6. [第二阶段：策略引擎与因子系统](#6-第二阶段策略引擎与因子系统)
7. [第三阶段：回测与模拟交易](#7-第三阶段回测与模拟交易)
8. [第四阶段：风控与执行](#8-第四阶段风控与执行)
9. [第五阶段：持久化与可观测性](#9-第五阶段持久化与可观测性)
10. [包结构规划](#10-包结构规划)
11. [开发原则](#11-开发原则)
12. [市场数据 API 现状](#12-市场数据-api-现状)
13. [当前能力矩阵](#13-当前能力矩阵)
14. [已知技术债](#14-已知技术债)
15. [术语表](#15-术语表)

---

## 1. 系统愿景

构建一个**实时加密货币交易信号引擎**，能够：

- 从多个交易所和数据源（Binance、Coinglass、OKX 等）实时获取市场数据
- 将原始数据标准化为统一的事件模型
- 通过可插拔的策略引擎生成交易信号
- 支持回测、模拟交易和实盘交易三种模式，复用同一套策略接口
- 具备完善的风控体系

**不做**的事情：
- 不构建完整的交易所客户端（不做下单执行，除非进入第四阶段）
- 不构建前端 UI（当前阶段）
- 不追求高频交易（目标是分钟级到小时级信号）

---

## 2. 技术栈

| 类别 | 技术选型 | 版本 | 说明 |
|------|---------|------|------|
| 语言 | Java | 17 | LTS 版本 |
| 框架 | Spring Boot | 3.5.5 | 主框架 |
| 构建 | Maven | 3.9+ | 无 Wrapper，需本地安装 |
| ORM | MyBatis-Plus | 3.5.14 | 数据库访问 |
| 数据库 | MySQL | 8.x | localhost:3306 |
| HTTP | OkHttp | 4.12.0 | 含 SOCKS 代理支持 |
| WebSocket | OkHttp WebSocket | 4.12.0 | **首选**（已有代理支持） |
| WebSocket（备选） | Tyrus | 2.1.3 | Jakarta WebSocket 客户端 |
| JSON | Jackson | — | Spring Boot 自带 |
| 工具 | Lombok / Hutool | — | 代码简化 |
| 测试 | JUnit 5 + AssertJ + Mockito + Awaitility | — | 第一阶段引入 |
| 技术指标 | TA4J | 待评估 | 需确认 Java 17 兼容版本 |

### 技术选型决策

**WebSocket 客户端：OkHttp 优先**

当前项目同时使用 Tyrus（Jakarta WebSocket）和 OkHttp。后续开发中，WebSocket 连接优先使用 OkHttp：
- 项目已有 OkHttp 依赖和 SOCKS 代理配置
- OkHttp 的 WebSocket API 更简洁，支持同步/异步
- 代理支持更直接，无需额外配置
- Tyrus 保留作为备选，逐步淡出

**技术指标库：TA4J 评估**

TA4J 是纯 Java 技术分析库，支持 MA、EMA、MACD、RSI、Bollinger Bands 等。需评估：
- Java 17 兼容性（TA4J 0.15+ 应支持）
- 是否满足需求（纯收盘价指标，不支持订单流分析）
- 备选方案：自行实现简单指标（MA/EMA），复杂指标用 TA4J

---

## 3. 架构演进路线

```
第一阶段（当前）   第二阶段           第三阶段           第四阶段           第五阶段
事件模型与数据管线  策略引擎与因子系统   回测与模拟交易      风控与执行         持久化与可观测性
────────────────  ────────────────  ────────────────  ────────────────  ────────────────
• Domain 模型      • 因子计算框架      • 回测引擎         • 风控规则引擎      • 时序数据库
• Event 模型       • TA4J 集成        • 模拟交易引擎      • 仓位管理         • 指标监控
• Connector 抽象   • 多策略支持        • 历史数据回放      • 下单执行         • 日志聚合
• 数据管线接通      • 策略热加载        • 性能报告         • 交易所 API 适配   • 告警系统
• 测试基础          • 因子持久化        • 策略参数优化      • 滑点模拟         • Dashboard
```

每个阶段独立可交付、可测试。阶段之间通过接口衔接，不产生硬耦合。

---

## 4. 目标架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                        数据源层 (Data Sources)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐            │
│  │ Binance  │  │ Coinglass│  │   OKX    │  │  其他...  │            │
│  │ WS/API   │  │ WS/API   │  │ WS/API   │  │          │            │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘            │
└───────┼──────────────┼──────────────┼──────────────┼────────────────┘
        │              │              │              │
┌───────▼──────────────▼──────────────▼──────────────▼────────────────┐
│                    连接器层 (Connector Layer)                        │
│  ┌─────────────────────────────────────────────────────────┐        │
│  │              MarketDataConnector (接口)                  │        │
│  │  connect() / disconnect() / subscribe() / health()      │        │
│  └─────────────────────────────────────────────────────────┘        │
│        │              │              │              │                │
│  ┌─────▼─────┐  ┌─────▼─────┐  ┌─────▼─────┐  ┌─────▼─────┐       │
│  │  Binance  │  │ Coinglass │  │   OKX     │  │  其他...   │       │
│  │ Connector │  │ Connector │  │ Connector │  │ Connector  │       │
│  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘       │
└────────┼──────────────┼──────────────┼──────────────┼───────────────┘
         │              │              │              │
┌────────▼──────────────▼──────────────▼──────────────▼───────────────┐
│                    标准化层 (Normalize Layer)                        │
│  ┌─────────────────────────────────────────────────────────┐        │
│  │              原始 Payload → MarketEvent                  │        │
│  │  BarEvent / LiquidationEvent / FundingRateEvent / ...   │        │
│  └─────────────────────────────────────────────────────────┘        │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────────┐
│                     事件总线 (Event Bus)                             │
│  ┌─────────────────────────────────────────────────────────┐        │
│  │  进程内 Typed EventBus（接口预留 Kafka/外部 MQ）          │        │
│  │  publish(MarketEvent) / subscribe(Class<T>, Consumer<T>)│        │
│  └─────────────────────────────────────────────────────────┘        │
└────────┬─────────────────┬─────────────────┬────────────────────────┘
         │                 │                 │
┌────────▼──────┐  ┌───────▼───────┐  ┌──────▼──────────────────────┐
│  因子计算层    │  │  策略引擎层    │  │  存储层 (Storage)           │
│  ┌──────────┐ │  │  ┌──────────┐ │  │  ┌──────────────────────┐  │
│  │ TA4J     │ │  │  │Strategy  │ │  │  │ KLine / Event 持久化 │  │
│  │ 自定义   │ │  │  │Engine    │ │  │  │ (未来: 时序数据库)    │  │
│  └──────────┘ │  │  └──────────┘ │  │  └──────────────────────┘  │
└───────────────┘  └───────┬───────┘  └─────────────────────────────┘
                           │
                    ┌──────▼──────┐
                    │  信号输出    │
                    │  SignalEvent │
                    └─────────────┘
```

---

## 5. 第一阶段：事件模型与数据管线

### 5.1 目标

将当前断裂的数据管线完全接通，建立标准化的事件模型，使数据能从 WebSocket 客户端流经事件总线到达策略引擎。

### 5.2 Domain 模型

#### 5.2.1 Exchange（交易所枚举）

```java
public enum Exchange {
    BINANCE, COINGLASS, OKX;
}
```

#### 5.2.2 MarketType（市场类型）

```java
public enum MarketType {
    SPOT,       // 现货
    FUTURES,    // 期货（USDT-M / Coin-M）
    PERPETUAL;  // 永续合约
}
```

#### 5.2.3 Instrument（交易工具）

```java
public record Instrument(
    Exchange exchange,
    MarketType marketType,
    String symbol,        // 如 "BTCUSDT"
    String baseAsset,     // 如 "BTC"
    String quoteAsset     // 如 "USDT"
) {}
```

不可变值对象。同一交易对在不同交易所/市场类型是不同的 Instrument。

#### 5.2.4 Timeframe（时间周期）

```java
public enum Timeframe {
    M1("1m"), M5("5m"), M15("15m"), M30("30m"),
    H1("1h"), H4("4h"), D1("1d");

    private final String code;
}
```

#### 5.2.5 MarketDataChannel（数据频道）

```java
public record MarketDataChannel(
    Exchange exchange,
    MarketType marketType,
    ChannelType type,     // KLINE, LIQUIDATION, FUNDING_RATE, OPEN_INTEREST, DEPTH, TICKER
    String symbol,
    Timeframe timeframe   // 可为 null（非 KLINE 类型）
) {}
```

#### 5.2.6 EventMetadata（事件元数据）

```java
public record EventMetadata(
    Exchange source,
    long exchangeTimestamp,   // 交易所时间戳（毫秒）
    long receivedTimestamp,   // 本地接收时间戳
    String rawMessageId       // 原始消息标识（用于去重/追踪）
) {}
```

#### 5.2.7 MarketEvent 接口

```java
public sealed interface MarketEvent
    permits BarEvent, LiquidationEvent, FundingRateEvent, OpenInterestEvent {

    Instrument instrument();
    EventMetadata metadata();
}
```

使用 sealed interface 确保类型安全，编译器可检查 exhaustiveness。

#### 5.2.8 BarEvent（K 线事件）

```java
public record BarEvent(
    Instrument instrument,
    EventMetadata metadata,
    Timeframe timeframe,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal volume,        // 基础资产成交量
    BigDecimal quoteVolume,   // 计价资产成交量
    boolean closed            // 是否为完整 K 线（非实时更新中）
) implements MarketEvent {}
```

#### 5.2.9 LiquidationEvent（爆仓事件）

```java
public record LiquidationEvent(
    Instrument instrument,
    EventMetadata metadata,
    OrderSide side,           // LONG / SHORT
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal quantityUsd,   // USD 计价
    String exchangeName       // 原始交易所名称（Coinglass 可能聚合多交易所）
) implements MarketEvent {}
```

#### 5.2.10 FundingRateEvent（资金费率事件）

```java
public record FundingRateEvent(
    Instrument instrument,
    EventMetadata metadata,
    BigDecimal fundingRate,
    BigDecimal predictedRate,
    long nextFundingTime      // 下次结算时间（毫秒）
) implements MarketEvent {}
```

#### 5.2.11 OpenInterestEvent（持仓量事件）

```java
public record OpenInterestEvent(
    Instrument instrument,
    EventMetadata metadata,
    BigDecimal openInterest,
    BigDecimal openInterestUsd
) implements MarketEvent {}
```

### 5.3 Connector 抽象

#### 5.3.1 MarketDataConnector 接口

```java
public interface MarketDataConnector {

    // 连接管理
    void connect();
    void disconnect();
    boolean isConnected();

    // 订阅管理
    void subscribe(SubscriptionRequest request);
    void unsubscribe(SubscriptionRequest request);

    // 健康检查
    ConnectorHealth health();

    // 数据回调注册
    void onEvent(Consumer<MarketEvent> handler);
}
```

#### 5.3.2 SubscriptionRequest

```java
public record SubscriptionRequest(
    Exchange exchange,
    MarketType marketType,
    ChannelType channelType,
    String symbol,
    Timeframe timeframe
) {}
```

#### 5.3.3 ConnectorHealth

```java
public record ConnectorHealth(
    boolean connected,
    long lastMessageTimestamp,
    long messagesReceived,
    long reconnectCount,
    String lastError
) {}
```

### 5.4 事件总线改造

当前 `EventBus` 按 symbol 字符串订阅，只支持 `NewBarEvent`。需要改造为：

```java
public interface EventPublisher {
    <T extends MarketEvent> void publish(T event);
}

public interface EventSubscriber {
    <T extends MarketEvent> void subscribe(Class<T> eventType, Consumer<T> handler);
}

public interface MarketEventBus extends EventPublisher, EventSubscriber {}
```

实现类支持按事件类型过滤订阅，而非按 symbol。symbol 过滤由订阅者自行处理。

### 5.5 数据流改造

**改造前（当前状态）：**
```
Coinglass WS → CoinglassWebSocketClient → 本地静默聚合（数据丢失）
Binance WS → BinanceWebSocketClient → 仅日志输出（数据丢失）
DataCenter → EventBus → StrategyEngine → 从未被调用
```

**改造后（第一阶段目标）：**
```
Coinglass WS → CoinglassConnector → LiquidationNormalizer → LiquidationEvent → MarketEventBus
Binance WS → BinanceConnector → BarNormalizer → BarEvent → MarketEventBus
MarketEventBus → StrategyEngine → BaseStrategy.onEvent(MarketEvent)
```

### 5.6 策略命名修正

当前 `MACDStrategy` 实际监听 `LIQUIDATION` 指标，名不副实。

**方案：** 重命名为 `LiquidationSpikeStrategy`，作为示例策略：
- 监听 `LiquidationEvent`
- 当单笔爆仓金额超过阈值时生成信号
- 作为策略开发的参考实现

### 5.7 测试要求

| 测试场景 | 测试类型 | 说明 |
|---------|---------|------|
| Binance kline JSON → BarEvent | 单元测试 | 使用真实 sample JSON |
| Coinglass liquidation JSON → LiquidationEvent | 单元测试 | 使用真实 sample JSON |
| EventBus publish → StrategyEngine → strategy invoked | 集成测试 | 验证完整链路 |
| 异常 payload 不崩溃 | 单元测试 | 畸形 JSON、null 字段 |
| Connector 健康检查 | 单元测试 | 连接状态、重连计数 |

---

## 6. 第二阶段：策略引擎与因子系统

### 6.1 目标

- 集成 TA4J 技术指标库
- 建立因子计算框架（Factor Calculator）
- 支持多策略并行运行
- 策略可通过配置文件定义监听的 symbol 和 indicator

### 6.2 因子系统

因子（Factor）是从原始市场数据计算出的特征值：
- 技术指标因子：MA、EMA、MACD、RSI、Bollinger Bands
- 衍生品因子：资金费率、持仓量变化率、爆仓密度
- 跨市场因子：BTC dominance、交易所资金流入流出

### 6.3 策略接口演进

```java
public interface Strategy {
    String name();
    Set<MarketEventType> listenedEvents();
    void onEvent(MarketEvent event);
    void onTimer(long timestamp);  // 定时触发（用于周期性检查）
}
```

---

## 7. 第三阶段：回测与模拟交易

### 7.1 目标

- 回测引擎：加载历史数据，回放事件，评估策略表现
- 模拟交易引擎：实时数据 + 虚拟资金，验证策略
- **关键约束：回测、模拟、实盘必须复用同一套策略接口和事件模型**

### 7.2 事件回放

```
历史数据文件/数据库 → EventReplayer → MarketEventBus → Strategy → SignalEvent → PerformanceReport
```

---

## 8. 第四阶段：风控与执行

### 8.1 风控规则

- 单笔最大亏损
- 每日最大亏损
- 最大持仓量
- 相关性限制（BTC/ETH 同向持仓上限）
- API 调用频率限制

### 8.2 执行层

- 交易所 API 适配（Binance / OKX）
- 限价单 / 市价单
- 滑点控制
- 订单状态追踪

**注意：** OKX API 会有 WebSocket 升级 notice 和动态价格限制参数，不能硬编码 price limit。

---

## 9. 第五阶段：持久化与可观测性

### 9.1 持久化

- K 线数据：时序数据库（QuestDB / TimescaleDB）或 MySQL
- 事件日志：用于审计和回放
- 策略信号：用于绩效分析

### 9.2 可观测性

- Micrometer 指标 → Prometheus → Grafana
- 结构化日志 → ELK / Loki
- 告警：信号异常、连接断开、策略延迟

---

## 10. 包结构规划

### 10.1 近期单 Maven 模块包布局

```
src/main/java/com/tj/crypto
├── common/                    # 通用工具和基础类
│   ├── domain/               # 共享领域模型（Exchange, Instrument, Timeframe 等）
│   └── util/                 # 工具类
│
├── marketdata/                # 市场数据层
│   ├── connector/            # 连接器抽象和实现
│   │   ├── MarketDataConnector.java
│   │   ├── binance/          # Binance 连接器
│   │   ├── coinglass/        # Coinglass 连接器
│   │   └── okx/              # OKX 连接器（未来）
│   ├── normalize/            # 数据标准化
│   │   ├── BarNormalizer.java
│   │   ├── LiquidationNormalizer.java
│   │   └── ...
│   └── model/                # 市场数据事件模型
│       ├── MarketEvent.java
│       ├── BarEvent.java
│       ├── LiquidationEvent.java
│       ├── FundingRateEvent.java
│       └── OpenInterestEvent.java
│
├── event/                     # 事件总线
│   ├── MarketEventBus.java
│   └── InMemoryEventBus.java
│
├── factor/                    # 因子计算（第二阶段）
│   ├── FactorCalculator.java
│   └── technical/            # 技术指标因子
│
├── strategy/                  # 策略引擎
│   ├── StrategyEngine.java
│   ├── BaseStrategy.java
│   └── impl/                 # 策略实现
│       └── LiquidationSpikeStrategy.java
│
├── backtest/                  # 回测引擎（第三阶段）
├── risk/                      # 风控（第四阶段）
├── execution/                 # 执行层（第四阶段）
│
├── storage/                   # 持久化（第五阶段）
├── config/                    # Spring 配置
├── service/                   # 服务层
└── controller/                # API 控制器
```

### 10.2 长期多 Maven 模块布局

```
crypto-common
crypto-marketdata
crypto-event
crypto-storage
crypto-factor
crypto-strategy
crypto-backtest
crypto-risk
crypto-execution
crypto-portfolio
crypto-api
crypto-app
```

**渐进式迁移：** 不一次性移动所有文件。第一阶段先创建新包结构，将新代码放入正确位置，旧代码在后续阶段逐步迁移。

---

## 11. 开发原则

### 11.1 面向文档编程

每次新增或修改功能，必须同步更新文档：
- **为什么这样设计** — 决策背景和权衡
- **优点** — 为什么选择这个方案
- **模块能做什么 / 不能做什么** — 明确边界
- **如何测试** — 测试策略
- **后续扩展点** — 未来如何演进

### 11.2 代码组织

- **小文件原则：** 200-400 行典型，800 行上限
- **单一职责：** 每个类只有一个改变的理由
- **按领域拆包：** 不按技术层拆分，按业务领域
- **接口隔离：** 依赖抽象，不依赖具体实现

### 11.3 不重复造轮子

- 标准技术指标：优先评估 TA4J
- WebSocket 连接：优先使用 OkHttp（已有代理支持）
- 测试框架：JUnit 5 + AssertJ + Mockito + Awaitility
- 不引入重型中间件（Kafka、Redis、QuestDB）除非确实需要

### 11.4 接口层隔离

所有外部 API 适配必须有接口层：
```
策略代码 → Strategy 接口 → MarketEvent（内部模型）
                           ↑
数据源代码 → Connector 接口 → Normalizer → MarketEvent
```

策略代码**永远不能**依赖 Binance/OKX/Coinglass 的原始 payload。

### 11.5 密钥安全

- 所有 API key 从代码和 application-dev.yml 中移出
- 使用环境变量或本地未提交配置（.gitignore）
- 日志中不打印密钥
- 配置文件中使用 `${ENV_VAR:default}` 占位符

### 11.6 测试驱动

- 每次代码改动后必须添加或更新测试
- 执行 `mvn test` 验证
- 若测试无法运行，必须说明原因和下一步
- 测试覆盖率目标：80%+

### 11.7 不可变性

所有事件模型和值对象必须不可变：
- 使用 `record`（Java 16+）
- 集合使用 `Collections.unmodifiableList()` 或 `List.of()`
- 不提供 setter

---

## 12. 市场数据 API 现状

### 12.1 Binance USD-M Futures WebSocket

**重要变更：** 现在区分 `/public`、`/market`、`/private` 路径：

| 数据类型 | 路径 | 说明 |
|---------|------|------|
| kline | `/market` | 更新频率可达 250ms |
| markPrice | `/market` | 包含 mark/index/funding/next funding time |
| forceOrder | `/market` | 每 1000ms 最大推送一次，**不是完整爆仓数据** |
| depth | `/public` | 订单簿深度 |
| account/trade | `/private` | 需要签名 |

**关键注意：**
- 不要继续硬编码 `wss://fstream.binance.com/ws/...` 作为通用地址
- forceOrder 只推最大爆仓单，不可作为完整爆仓数据源
- kline 更新频率 250ms，需要考虑去重和合并

### 12.2 Coinglass API

提供丰富的衍生品数据：
- 爆仓数据：liquidation orders / history / heatmap / map
- 持仓量：open interest（多交易所聚合）
- 资金费率：funding rate（多交易所）
- ETF 数据
- 多空比

是因子系统的重要数据来源。

### 12.3 OKX API（未来）

- WebSocket 升级 notice：连接可能被要求升级
- 动态价格限制参数：不能硬编码
- 需要签名认证

---

## 13. 当前能力矩阵

| 功能 | 状态 | 说明 |
|------|------|------|
| Coinglass WS 连接 | ✅ 已实现 | OkHttpCoinglassWebSocketClient + CoinglassProperties（环境变量配置） |
| Coinglass 爆仓数据接收 | ✅ 已实现 | CoinglassLiquidationNormalizer → LiquidationEvent → MarketEventBus |
| Binance WS 连接 | ✅ 已实现 | OkHttpBinanceWebSocketClient + BinanceKlineNormalizer → BarEvent → MarketEventBus |
| Binance KLine 解析 | ✅ 已实现 | BinanceKlineNormalizer 标准化为 BarEvent |
| 历史数据回填 | ✅ 已实现 | BinanceHistoricalDataProvider 支持按时间范围批量下载 |
| MarketEventBus 事件分发 | ✅ 已实现 | InMemoryEventBus，按事件类型 typed pub/sub |
| StrategyEngine | ✅ 已实现 | 订阅 MarketEvent，异步分发到 Strategy beans |
| 策略实现 | ✅ 6 个策略 | MacdCross / RsiCross / LiquidationSpikeV2 / SuperTrend / BollingerBreakout / AtrTrailingStop |
| 策略热加载 | ✅ 已实现 | StrategyManager 支持运行时启用/禁用，AdminController 提供 REST API |
| 策略组合管理 | ✅ 已实现 | StrategyPortfolio 支持多策略资金分配 |
| 策略评估 | ✅ 已实现 | StrategyEvaluator 策略绩效评估 |
| 因子系统 | ✅ 14 个因子 | 9 技术因子 + 5 衍生品因子 + 7 框架/分析类，FactorRegistry 管理 |
| 因子分析 | ✅ 已实现 | FactorAnalyzer + FactorReturnStats 因子收益分析 |
| 测试 | ✅ 553 测试 | 71 测试类，覆盖全部核心模块（1 skipped） |
| 回测 | ✅ 已实现 | BacktestEngine + EventReplayer + VirtualAccount + PerformanceReport + PortfolioBacktestEngine |
| 组合回测 | ✅ 已实现 | PortfolioBacktestEngine 支持多策略独立回测 + 合并报告 |
| Walk-Forward 优化 | ✅ 已实现 | WalkForwardOptimizer 支持滚动窗口参数优化 |
| 参数优化 | ✅ 已实现 | ParameterOptimizer 支持网格搜索参数优化 |
| 模拟交易 | ✅ 已实现 | PaperTradingEngine + VirtualAccount |
| 期货账户 | ✅ 已实现 | FuturesAccount + FuturesPosition + MarginMode |
| 风控 | ✅ 已实现 | RiskEngine + 6 条规则 + KillSwitch + DrawdownGuard + PositionSizer |
| 执行 | ✅ 已实现 | ExecutionEngine + OrderStateMachine + FixedSlippageModel + Order 模型 |
| 费用模型 | ✅ 已实现 | MakerTakerFeeModel + FeeProperties |
| 持久化 | ✅ 已实现 | BarEvent/SignalEvent/TradeRecord/RawMessage converter+mapper+service + EventPersistenceListener |
| 数据覆盖率 | ✅ 已实现 | DataCoverageService + CoverageReport 检测数据缺口 |
| 数据血缘 | ✅ 已实现 | DataLineageService 追踪数据来源和版本 |
| 数据自动回填 | ✅ 已实现 | AutoBackfillService + MarketDataPersistenceService |
| 数据质量检查 | ✅ 已实现 | DataQualityChecker + DataQualityReport |
| Admin 管理 API | ✅ 已实现 | AdminController: /status, /signals, /factors, /strategies, /health |
| Admin 认证鉴权 | ✅ 已实现 | AuthService + AuthInterceptor + Role 枚举 |
| 配置版本管理 | ✅ 已实现 | ConfigVersionService + ConfigSyncService + ConfigVersionDO |
| 审计日志 | ✅ 已实现 | AuditService + AuditLogDO |
| 可观测性 | ✅ 已实现 | SystemMetrics + AlertService + AlertRule + MetricsSnapshot |
| 前端控制台 | ✅ 已实现 | React + Ant Design + Vite，6 个页面（Dashboard/Factors/Signals/Strategies/Risk/Backtests） |

---

## 14. 已知技术债

| 编号 | 问题 | 严重程度 | 状态 |
|------|------|---------|------|
| TD-1 | ~~Coinglass API key 硬编码在 URI 中~~ | 🔴 高 | ✅ 已修复 — 通过 CoinglassProperties 配置 |
| TD-2 | ~~数据库密码硬编码在 application-dev.yml~~ | 🔴 高 | ✅ 已修复 — 使用环境变量占位符 |
| TD-3 | BinanceWebSocketClient 使用 Tyrus，与 OkHttp 不一致 | 🟡 中 | 待修复 — OkHttpBinanceWebSocketClient 已实现，旧 Tyrus 客户端待移除 |
| TD-4 | ~~Symbol 枚举的 desc 值为 "111"/"222" 无意义~~ | 🟡 中 | ✅ 已修复 — 旧 Symbol/Indicator 枚举已移除，使用 Instrument record |
| TD-5 | ~~Indicator 枚举的 desc 值为 "111"/"222" 无意义~~ | 🟡 中 | ✅ 已修复 — 旧枚举已移除 |
| TD-6 | TradeSymbolDO 类名有误导性（映射 ID 生成器表） | 🟡 中 | 待修复 |
| TD-7 | ~~EventBus 只支持按 symbol 订阅，不支持按事件类型~~ | 🟡 中 | ✅ 已修复 — InMemoryEventBus 支持按事件类型 typed pub/sub |
| TD-8 | ~~StrategyEngine.callOnEvent() 为 private，无法被外部调用~~ | 🔴 高 | ✅ 已修复 — StrategyEngine 订阅 MarketEventBus，公开分发 |
| TD-9 | ~~无测试代码~~ | 🔴 高 | ✅ 已修复 — 71 测试类，553 测试用例，全部通过 |
| TD-10 | CoinglassWebSocketClient 中 KLineData 直接 set 修改（可变） | 🟡 中 | 待修复 — KLineData 仍为可变 DTO |
| TD-11 | ~~application.yml 中数据库连接使用默认密码 "111"~~ | 🔴 高 | ✅ 已修复 — 使用环境变量占位符 |
| TD-12 | ~~Binance WS URL 硬编码旧地址格式~~ | 🟡 中 | ✅ 已修复 — 通过 ConnectorProperties 分离现货/永续地址 |
| TD-13 | 前端构建 chunk 超过 500 kB | 🟢 低 | 待优化 — 考虑 code-splitting |

---

## 15. 术语表

| 术语 | 含义 |
|------|------|
| Bar / KLine | K 线，包含 OHLCV 数据 |
| Liquidation | 爆仓/强制平仓 |
| Funding Rate | 永续合约资金费率 |
| Open Interest | 持仓量 |
| MarketEvent | 内部标准化的市场事件 |
| Normalizer | 将外部原始数据转换为 MarketEvent 的组件 |
| Connector | 管理与外部数据源连接的组件 |
| Factor | 从原始数据计算出的特征值 |
| Strategy | 策略，监听事件并生成信号 |
| SignalEvent | 策略输出的交易信号 |
| OHLCV | Open/High/Low/Close/Volume |
| Perpetual | 永续合约 |
| Mark Price | 标记价格 |
| Index Price | 指数价格 |

---

## 附录：参考资料

- [Binance USD-M Futures WebSocket Market Streams](https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams)
- [Binance Kline Streams](https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Kline-Candlestick-Streams)
- [Binance Mark Price Stream](https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Mark-Price-Stream)
- [Binance Liquidation Order Streams](https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Liquidation-Order-Streams)
- [Coinglass API 文档](https://docs.coinglass.com/)
- [OKX API Changelog](https://www.okx.com/docs-v5/log_en/)
- [TA4J GitHub](https://github.com/ta4j/ta4j)
- [QuestDB](https://questdb.com/)
