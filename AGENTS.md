# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

实时加密货币交易信号引擎。通过 WebSocket 从 Coinglass（爆仓数据）和 Binance（K线数据）获取实时行情，基于事件驱动架构聚合、分发到策略引擎，生成交易信号。

## Tech Stack

- Java 17 / Spring Boot 3.5.5
- Maven（无 Maven Wrapper，需本地安装 `mvn`）
- MyBatis-Plus 3.5.14 + MySQL（数据库 `id_generator`，localhost:3306）
- OkHttp 4.12.0（HTTP 客户端，SOCKS 代理 127.0.0.1:10808）
- Tyrus 2.1.3（Jakarta WebSocket 客户端）
- Lombok / Hutool / Jackson

## Build & Run

```bash
mvn clean package                                    # 构建
mvn spring-boot:run                                  # 运行（默认 profile: dev，端口 51102）
mvn spring-boot:run -Dspring-boot.run.profiles=pro   # 指定 profile 运行
java -jar target/crypto-trading-1.0.0.jar            # JAR 运行
mvn test                                             # 测试（目前无测试用例，无 src/test/ 目录）
```

## Architecture

事件驱动的数据流管线：

```
Coinglass WS → CoinglassWebSocketClient → (本地 1min/5min KLine 聚合)
                                               ↓ (尚未连接)
                                          DataCenter.updateKline()
                                               ↓
                                          EventBus.publish(NewBarEvent)
                                               ↓ (尚未连接)
                                          StrategyEngine.callOnEvent()
                                               ↓
                                          BaseStrategy.onEvent()
```

**管线当前状态：尚未完全接通。** CoinglassWebSocketClient 在本地聚合 KLine 但未调用 DataCenter；StrategyEngine.callOnEvent() 是 private 且未被任何地方调用。

### 核心组件 (`central/`)

| 类 | 职责 |
|---|---|
| `DataCenter` | 中心数据枢纽。持有内存 KLine 数据（ConcurrentHashMap），内联创建 EventBus，发布 NewBarEvent。asyncPersist() 已预留但未启用。 |
| `EventBus` | 简单发布/订阅。按 symbol 注册 `Consumer<NewBarEvent>`，同步派发（非 Spring Bean，由 DataCenter 持有）。 |
| `StrategyEngine` | 注入所有 BaseStrategy Bean，按 Symbol + Indicator 匹配分发事件到 tjTaskExecutor 线程池。callOnEvent() 当前为 private。 |
| `BaseStrategy` | 策略抽象类。子类需实现 `getListenSymbol()`、`getListenIndicator()`、`onEvent(Symbol, Indicator)`。 |
| `MACDStrategy` | 目前唯一策略实现，监听 BTC_USDT + ETH_USDT 的 LIQUIDATION 指标，方法体均为 stub。 |

### 数据接入层 (`client/`)

- `CoinglassWebSocketClient`：连接 `wss://open-ws.coinglass.com/ws-api`，接收爆仓订单流。在 `handleData()` 中按 symbol 过滤并聚合到 `ConcurrentLinkedDeque<KLineData>` 队列（1min/5min 时间窗口）。
- `BinanceWebSocketClient`：连接 Binance 期货 WebSocket，解析 KLine 事件。**当前已注释掉**（BinanceWebSocketService 中 @PostConstruct 和 @Scheduled 被注释）。

### 生命周期管理 (`service/`)

- `CgWebSocketService`：@PostConstruct 启动 Coinglass 连接，@Scheduled(fixedRate=3000) 健康检查与重连。
- `BinanceWebSocketService`：已注释，未激活。

### 启动流程

1. `CgWebSocketService.init()` 提交 `CoinglassWebSocketClient::connect()` 到线程池
2. 等待 5 秒后发送 `liquidationOrders` 频道订阅消息
3. 每 3 秒健康检查，断线自动重连

## Key Packages

| 包路径 | 职责 |
|---|---|
| `com.tj.crypto.central` | 策略引擎核心：EventBus、DataCenter、StrategyEngine、BaseStrategy |
| `com.tj.crypto.client` | 外部 WebSocket 客户端（Coinglass、Binance） |
| `com.tj.crypto.config` | Spring 配置（OkHttp 代理、线程池、WebSocket 容器） |
| `com.tj.crypto.service` | WebSocket 生命周期管理（连接、订阅、健康检查） |
| `com.tj.crypto.enums` | Symbol（交易对）和 Indicator（指标类型）枚举 |
| `com.tj.crypto.entity` | 数据库实体及基类（PhysicsTimeBaseDO、Logic、User） |
| `com.tj.crypto.mapper` | MyBatis-Plus Mapper（BaseMapperX 提供通用 CRUD） |
| `com.tj.crypto.pojo.dto` | 数据传输对象（CgResultDTO、LiquidationOrder、KLineData） |

## Configuration

- `application.yml`：主配置（端口 51104，MySQL 连接）
- `application-dev.yml`：开发配置（端口 51102，SQL debug 日志，Binance WS URL，API keys）
- Spring profiles：dev（默认）、sit、uat、pro
- 所有出站 HTTP 请求经 SOCKS 代理 127.0.0.1:10808（OkHttpConfig 统一配置）
- WebSocket 代理在 WebSocketConfig 中通过 WebsocketProperties 配置

## Conventions

- DTO 使用 `@Data`（Lombok），实体继承关系参考 `entity/base/`（PhysicsTimeBaseDO → createTime/updateTime）
- 新增策略：继承 BaseStrategy，注册为 Spring Bean，StrategyEngine 通过 `List<BaseStrategy>` 自动发现
- 新增 Indicator：在 `enums/Indicator` 中添加枚举值
- 新增交易对：在 `enums/Symbol` 中添加，并同步更新 CoinglassWebSocketClient 中的过滤 symbol 列表
- 线程池配置见 `config/ThreadPoolConfig`：core = CPU 核心数，max = 4x core，队列 200，CallerRunsPolicy

## 注意事项

- `TradeSymbolDO` 映射的是 ID 生成器表 `biz_tiny_id`，不是交易对表（类名有误导性）
- CoinglassWebSocketClient.connect() 中 API key 硬编码在 URI 中
- 数据库持久化（DataCenter.asyncPersist）和 Binance 客户端当前均未激活
