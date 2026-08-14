# 加密货币实时交易研究平台：项目经历与交易所面试材料

> 核验日期：2026-07-15
>
> 适用对象：有多年 Java 全栈经验、面试交易所/券商交易系统岗位的开发者
>
> 结论先行：本项目最准确的定位是“单节点、事件驱动的实时行情、策略研究、回测、模拟交易与 OMS 基础平台”，不是撮合引擎，也不是已经通过生产容量认证的交易所核心。

## 0. 使用说明：五类数字必须分开

面试中统一使用以下标签，避免把设计目标说成线上成绩：

- **【代码事实】**：当前仓库能直接证明的实现。
- **【验证事实】**：本次在指定环境中实际执行得到的结果。
- **【公式推导】**：从配置或容量模型精确算出的值，不等同于实测吞吐。
- **【假设场景】**：为了讨论扩容而定义的业务负载。
- **【验收目标】**：下一阶段的 SLO/测试门槛，不是当前成果。
- **【当前缺口】**：代码中仍需修复或尚未闭环的部分。

严禁把后三类改写成“线上已经达到”。面试官若追问机器、持续时间、payload、P99、GC、网络和持久化口径，而回答不出来，这个数字反而会损害可信度。

---

## 1. 项目定位

### 1.1 一句话版本

我负责设计并实现了一套基于 Java 17、Spring Boot 的事件驱动型加密货币交易研究平台，统一接入 Binance、OKX、CoinGlass 行情，完成 K 线标准化、因子和策略计算、历史回测、模拟交易、风险控制、OMS、双分录账本、对账及管理控制台。

### 1.2 必须明确的边界

当前项目可以证明我理解并落地了以下交易领域问题：

- 多交易所行情语义统一、完整市场身份、forming/finalized K 线隔离。
- 有状态策略的分区互斥、历史回放和防未来函数。
- 模拟订单的资金冻结、状态机、客户端订单号、双分录账本和对账。
- 外部下单不确定状态 `UNKNOWN`、私有流更新和 fail-closed 写开关的设计。

当前项目不能描述为：

- 证券交易所撮合引擎、低延迟订单网关或 HFT 系统。
- 已经部署 Kafka、Pulsar、Redis、ClickHouse 或 FIX 的生产系统。
- 已经达到十万 TPS、亚毫秒 P99、五个九可用或端到端 exactly-once。
- 已经通过欧洲监管、券商生产准入或灾备验收。

### 1.3 当前可核验规模

【代码事实】默认配置：

- Binance：2 个 symbol × 2 个 market type × 1 个 timeframe = 4 条 K 线序列。
- OKX：4 个 native instrument × 1 个 timeframe = 4 条序列。
- CoinGlass：2 个 symbol × 2 个 market type × 1 个 timeframe = 4 条轮询序列；没有 API Key 时不会运行。
- 合计最多 12 条 1 分钟序列。

【公式推导】若 12 条序列全天健康：

```text
finalized bars/day = 12 × 1,440 = 17,280
average finalized write rate = 12 / 60 = 0.2 bar/s
```

这说明当前默认 universe 本身不是高吞吐场景。复杂设计的价值主要在正确性、可恢复性和后续演进，不在于宣称当前已有巨大流量。

### 1.4 本次验证证据

【验证事实】在 Windows、JDK 17 环境执行：

- `mvn test`：574 tests，0 failures，0 errors，0 skipped。
- `npm run lint`：通过。
- `npm run build`：通过；Vite 对大于 500 kB 的 chunk 给出告警，但构建成功。

注意：Maven 默认排除了 `stress`、`soak`、`external` 分组。因此 574 个测试证明的是离线功能与确定性契约，不证明生产吞吐、长稳性或真实交易所连通性。

### 1.5 当前可以量化的实现效果

| 项目 | 精确值 | 性质与边界 |
|---|---:|---|
| 默认 K 线序列 | 最多 12 | 配置推导；CoinGlass 无 Key 时不运行 |
| 1m finalized 理论量 | 17,280/day，平均 0.2/s | 公式推导，不是压测吞吐 |
| finalized 内存缓存 | 500/series，默认最多 6,000 | 另有每 series 至多一根 forming |
| 因子/策略/风控规则 | 14 / 7 / 7 | 当前 Spring 组件代码计数 |
| K 线刷盘 | 100 条触发或每 1s；buffer 上限 10,000 | “batch”当前仍是循环单条 upsert，满后拒绝 |
| Binance 重连退避 | 1、2、4、8…最大 60s | 有重连/重订阅，不等于完整 gap recovery |
| 默认离线测试 | 574 passed | 排除 stress/soak/external |
| 前端质量门 | lint + production build passed | 当前没有 candlestick 交易终端 |

这些数值都能被代码、配置或本次命令复核；它们不应被改写成“线上并发用户数”或“系统 TPS”。

---

## 2. 可直接放进简历的项目经历

### 2.1 项目名称与职责

**实时加密货币行情、策略研究与模拟交易平台｜核心开发/架构设计**

技术栈：Java 17、Spring Boot、MyBatis-Plus、MySQL、Flyway、OkHttp WebSocket、TA4J、React、TypeScript、Ant Design、ECharts。

### 2.2 推荐简历描述（当前事实版）

1. 设计事件驱动行情管线，将 Binance、OKX、CoinGlass 的异构消息标准化为不可变领域事件，以 `exchange + marketType + symbol + timeframe` 隔离市场序列，避免同名标的跨交易所、现货与永续数据串线。
2. 实现 K 线 forming/finalized 物理隔离，按 Binance `x`、OKX `confirm` 判定闭合；因子、策略、持久化和回测默认仅消费闭合 K 线，降低指标 repaint 与未来函数风险。
3. 构建 14 个技术/衍生品因子、7 个策略、组合与 walk-forward 回测框架，保存配置、成本假设、数据和随机种子快照，使结果可复现、可审计。
4. 将模拟交易从内存账户演进为事务化订单链路，落地资金预占、OMS 状态机、成交、余额/持仓、双分录账本、Outbox 框架和周期对账；外部订单使用唯一客户端订单号和 `UNKNOWN` 状态处理超时歧义。
5. 建设 React 管理台，覆盖行情、策略、回测、风控、模拟订单、配置版本与审计；当前默认离线测试 574 项全部通过，前端 lint/build 通过。

### 2.3 有实测报告后才能替换的性能版模板

```text
在【CPU/内存/JDK/GC/实例数/网络】环境下，使用【真实录制 payload、品种数、消息频率】
持续回放【时长】，在【是否包含解析、MQ ack、数据库持久化】口径下达到：
- 吞吐：【___ events/s】；5 秒突发：【___ events/s】
- receive-to-canonical：p50【___】/ p95【___】/ p99【___】
- drop【___】、gap【___】、duplicate side effect【___】
- CPU【___】、max GC pause【___】、max queue lag【___】
```

没有这份报告，不要填写数字。

---

## 3. 面试开场讲稿

### 3.1 30 秒版本

> 这是一个实时加密货币行情、策略研究和模拟执行平台。我做的重点不是简单连 WebSocket，而是处理多交易所语义差异、K 线闭合状态、断线重连和重复回补，并把行情、因子、策略、回测、OMS、资金账本和对账串成可审计链路。当前代码是单节点研究与 paper-trading 基础，不是撮合引擎；高并发、Kafka、FIX 和欧洲合规部分，我会明确区分已实现能力与下一阶段容量设计。

### 3.2 3 分钟版本

> 第一版为了尽快验证策略，我用 OkHttp WebSocket、同步进程内事件总线和 MySQL 跑通了 Binance 行情到策略信号的链路。接入 OKX 和 CoinGlass 后，我发现真正困难的不是 JSON 解析，而是数据身份和业务语义：同一个 BTCUSDT 在不同交易所、现货和永续不是同一条序列；OKX 永续的 `vol` 是合约张数，也不能直接当成基础币成交量。
>
> 我因此把 connector、normalizer 和 canonical event 分层，以交易所、市场类型、标的和周期构造完整 key；缓存将未收盘和已收盘 K 线物理隔离，策略、因子和回测只读取 finalized 数据。重连和 REST 回补会带来重复，我在缓存和数据库使用自然键 upsert，并加了指数退避和自动重新订阅。
>
> 后来我继续处理交易链路。外部下单超时不能直接理解为失败，否则重试可能产生重复订单。我引入客户端订单号、订单状态机和 `UNKNOWN`，通过私有流、REST 查单和对账让状态收敛；模拟交易用事务内资金冻结、成交、余额、持仓和双分录账本保证本地一致性。
>
> 当前同步总线适合这个小 universe，但我也识别出它在扩容后的边界：网络回调会被慢消费者反压，所谓 batch 仍是循环单条 upsert，策略消费没有完整的乱序和幂等门。因此生产演进会按 series key 分区到有界队列；只有出现跨节点重放、审计和独立扩容需求时，才引入 Kafka/Pulsar。订单、余额和账本仍以强事务数据库为真相源。

---

## 4. 假设业务规模：用于讨论扩容，不是当前成绩

### 4.1 业务背景

【假设场景】系统从个人研究扩展为 B2B 行情与策略研究平台：

- 300 个 symbol。
- 2 个交易所。
- 每个交易所同时覆盖 SPOT、PERPETUAL。
- 5 个周期：1m、5m、15m、1h、4h。
- 300 个具名研究用户，80 个并发控制台会话。
- 最多 24 个并发历史回测任务。
- 订单意图平均 200/s，5 秒突发 1,000/s；此处仅作为 OMS 容量输入。

### 4.2 精确容量计算

【公式推导】总序列数：

```text
300 symbols × 2 venues × 2 market types × 5 timeframes = 6,000 series
```

单个 venue/market/symbol 每天的闭合 K 线数：

```text
1m:  1,440
5m:    288
15m:    96
1h:     24
4h:      6
sum: 1,854 bars/day
```

全量：

```text
1,854 × 300 × 2 × 2 = 2,224,800 finalized bars/day
2,224,800 × 365 = 812,052,000 finalized bars/year
average finalized rate = 2,224,800 / 86,400 = 25.75 bars/s
```

【假设场景】若压测器让每条 forming K 线每秒更新一次：

```text
6,000 series × 1 update/s = 6,000 events/s
if all 7 registered strategies evaluate every event:
6,000 × 7 = 42,000 strategy-event evaluations/s
```

真实交易所更新频率随产品、周期、活跃度和协议变化，6,000 events/s 是可重复的发生器输入，不是交易所承诺。

### 4.3 为什么这个模型会推动架构变化

- 闭合 K 线平均写入并不高，真正的峰值来自 forming 更新、逐笔成交、深度和策略 fan-out。
- 8.12 亿行/年已不适合把全部历史分析继续压在单个未分区 MySQL 表上。
- 200/s 订单意图和 1,000/s 突发不能用一个全局锁处理，但同一资金账户仍必须串行化或做严格版本控制。
- 24 个回测任务会争用 CPU、内存和数据库，需要任务配额、隔离池和数据本地化；当前回测执行器只有 core=2、max=4、queue=32，不能声称已承载该目标。

---

## 5. 一整个真实工程演进故事

以下是基于现有代码可以佐证的工程演进。只有自己确实参与过的决策才能使用第一人称；假设负载暴露的问题应说“容量评审/故障注入发现”，不要说成虚构的线上事故。

### 阶段一：先跑通，再发现“BTCUSDT 并不是唯一身份”

**问题表现**

最初按 `symbol + timeframe` 缓存行情时，Binance BTCUSDT 现货、永续和其他来源可能写进同一时间序列，导致因子、回测、持仓估值交叉污染。

**排查思路**

从“为什么同一分钟 OHLC/volume 不一致”反查上游，发现 symbol 是展示名称，不是完整业务主键。交易所、市场类型、原生 instrument、结算币、合约乘数和日历都可能改变语义。

**方案对比**

- 在 symbol 上拼前缀：改动小，但仍是字符串约定，容易遗漏。
- 每个 connector 维护独立缓存：隔离明显，但下游重复实现，无法统一回测。
- 建立 `InstrumentId` 和 `MarketSeriesKey`：类型安全，适合贯穿缓存、策略和存储。

**当前落地**

使用 `exchange + marketType + symbol + timeframe` 作为完整 series key；数据库自然键为 `(exchange, market_type, symbol, timeframe, open_time)`。

**效果与边界**

- 解决了当前 Binance/OKX/CoinGlass、SPOT/PERPETUAL 的跨市场串线。
- 尚未把 `calendar/timezone`、source revision、底层价格场所完整纳入身份；若接入 Binance UTC+8 日线或证券交易日历，需要扩展 key。

### 阶段二：K 线会 repaint，不能把“最新”当“最终”

**问题表现**

同一根 K 线在收盘前不断更新。如果每次更新都进入技术指标、策略和数据库，会造成信号反复、重复写入；回测如果看到了该 bar 的最终 high/low/close，则引入未来信息。

**排查思路**

明确三种时间与状态：bar 的开收盘时间、交易所事件时间、本机接收/处理时间；再区分 forming update 与 finalized fact。

**方案对比**

- 只订阅闭合 K 线：实现简单，但 UI 和盘中策略失去实时 bar。
- forming 和 finalized 共表覆盖：查询必须处处带状态，容易把未完成数据送进回测。
- 在缓存层物理隔离：前端可看 forming，研究和持久化默认只读 finalized。

**当前落地**

- Binance 读取 `x`；OKX 读取 `confirm`。
- `InMemoryBarCache` 分离 forming/finalized，同 openTime upsert。
- 策略、因子历史、回测、持久化默认只处理 `closed=true`。
- 每条序列缓存 500 根 finalized；1m 数据约 8 小时 20 分钟。默认 12 条序列最多 6,000 根 finalized，另有每序列至多一根 forming。

**效果与边界**

可以准确说“降低 repaint、重复入库和 bar 级回测未来函数风险”，不能说“消除了所有未来函数”；数据筛选、参数优化、资金费率、交易成本和幸存者偏差仍需控制。

### 阶段三：断线恢复不是“重连成功”这么简单

**问题表现**

网络闪断、24 小时强制断连、服务重启后，会同时出现缺口、重复推送、旧 socket 迟到回调和 REST 回补与 live 流交叉。

**排查思路**

将恢复拆成四个问题：连接是否存活、订阅是否恢复、时间网格是否连续、下游副作用是否幂等。`connected=true` 不能代表数据健康。

**方案对比**

- 仅指数退避重连：只能恢复未来数据，历史 gap 永久存在。
- 重连后固定回拉 N 根：简单，但停机时长不确定，仍可能漏。
- 持久化每条 series watermark，按缺口区间分页回补，并设置 live barrier：正确但状态机更复杂。

**当前落地**

- Binance Spot/Perpetual 独立 session，指数退避、重新订阅，并对旧 socket callback 做 fencing。
- 有 Binance、OKX、CoinGlass 历史 provider、覆盖检查与 upsert。
- 缓存和 MySQL 用自然键吸收重复。

**当前缺口**

- 重连没有完整地从持久化 checkpoint 自动计算精确缺口。
- DB/cache 幂等不等于策略幂等；重复 finalized bar 仍可能再次触发策略。
- OKX/CoinGlass 缺少与 Binance 同等级的 connection-generation fencing 和 pong deadline。
- CoinGlass watermark 在内存中，长时间停机不能依靠当前两 bucket 轮询完整恢复。

**生产演进**

```text
SeriesKey partition
  → connection generation fencing
  → bounded reorder window
  → finalized-bar state machine
  → idempotency gate
  → strategy/factor
  → idempotent sink
```

### 阶段四：同步事件总线开始放大慢消费者

**问题表现**

容量回放中，一旦策略或数据库变慢，OkHttp `onMessage` 线程会被同步 `publish()` 链路占住。持久化虽然提交线程池，但共享队列满后 `CallerRunsPolicy` 会把工作推回调用线程。

**根因**

- `InMemoryEventBus` 同步遍历 handler。
- 行情、信号和持久化缺少 bulkhead。
- 持久化缓冲 100 条触发、最多 10,000 条，满后拒绝；失败没有 durable WAL。
- `upsertBatch` 实际是循环单条 upsert，并非 multi-values/JDBC batch。

**方案横向比较**

- 扩大线程池/无界队列：只能延后过载，最终变成长尾延迟或 OOM。
- Disruptor：适合同 JVM 低分配、有序 fan-out；不提供跨机持久化和故障回放。
- Kafka/Redpanda：适合持久化日志、重放、消费组和独立扩容；只保证 partition 内顺序，外部数据库副作用仍需幂等。
- Pulsar：多租户、存储计算分离、Geo 能力突出；运维和消费语义更复杂。

**我的选择逻辑**

当前 12 条 1m 序列继续使用简单进程内总线是合理的。先拆 ingestion、normalization、strategy、persistence、order executor 的有界队列和指标；当出现跨节点重放、消费者独立伸缩和审计保留需求，再引入 Kafka/Pulsar，而不是为了技术栈堆砌提前分布式化。

### 阶段五：回测“能跑”不等于结果可信

**问题表现**

策略收益异常漂亮，常见原因不是策略强，而是用到了未闭合 bar、实时缓存、同 bar 成交、忽略手续费/滑点，或反复调参后只展示最佳组合。

**当前落地**

- 只消费闭合 bar，并使用 as-of 历史上下文。
- 组合历史回测只允许 `BarHistoryFactorCalculator`，不从实时内存合成历史因子。
- 保存 run/trade/equity/signal、参数、成本假设、数据和随机种子快照。
- 有 walk-forward、bootstrap、概率 Sharpe 等稳健性分析；少于 30 笔闭合交易会提示统计功效不足。

**横向边界**

- Bar 级执行模型：适合研究和中低频策略比较。
- L2/order-by-order replay：能模拟排队、部分成交和盘口冲击，但需要完整深度、sequence 和撮合规则。
- 当前参数化 spread/impact/partial-fill 只能做近似，不能声称等价于交易所撮合仿真。

### 阶段六：下单超时后，最危险的动作是直接重试

**问题表现**

发送订单后 HTTP 5xx 或超时，本地不知道交易所是否已经接受。若当成失败并重新生成订单 ID，可能双重成交；若当成成功，又可能永久漏单。

**核心认知**

网络和外部交易所无法与本地数据库构成一个 ACID 事务。端到端 exactly-once 不是现实承诺。

**当前落地**

- 唯一 `clientOrderId`，重复请求先校验是否为同一业务订单。
- 状态机包含 `UNKNOWN`；异常后保持可对账状态而非盲目重试。
- 私有订单流按累计成交量计算 delta，结合外部成交 ID 做幂等。
- 模拟交易在本地事务内完成资金冻结、订单、成交、余额、持仓、双分录账本和 Outbox 记录。
- 实盘写默认关闭，凭证存在也不会自动允许写单。

**严谨表述**

> 行情与命令采用 at-least-once 输入；本地通过幂等键和事务保证副作用只生效一次；外部订单通过 UNKNOWN、私有流、REST 查单和周期对账最终收敛。Kafka EOS 也不能自动覆盖 MySQL 和交易所副作用。

**当前缺口**

- 实盘订单的启动恢复、持续 UNKNOWN 扫描和全量 venue 对账未闭环。
- Outbox 有表、租约、重试和死信框架，但没有已接入 Kafka 的 handler，不能声称消息已可靠发布。
- kill switch 仍是单 JVM 内存状态，不满足集群级控制。

### 阶段七：同一账户要正确，通常意味着局部串行化

**问题表现**

同一账户同时下两笔买单时，如果都先读可用余额再扣减，可能超买；多个实例上的 Java `synchronized` 也无法保护数据库状态。

**当前落地**

模拟下单对账户/余额使用数据库行锁，在事务内检查、冻结和更新。同账户被串行化，多账户仍可并行。

**扩容选择**

- 全局锁：简单但吞吐最差，不可用。
- 乐观版本重试：冲突低时好，热点账户会反复失败。
- `accountId` 分区 single-writer/actor：同账户有序，多账户并行；数据库版本和账本约束作为最终防线。

订单分区键应是 `accountId` 或资金账户，不应复用行情的 series key。

### 阶段八：从“有日志”走向“可审计、可恢复、可监管”

**问题表现**

欧洲券商/交易所客户关心的不只是功能：谁改了参数、哪个版本产生订单、时钟是否可追溯、故障能否恢复、供应商能否退出、数据在哪个地域、事件多久报告。

**当前基础**

配置版本、审计记录、OMS journal、回测快照、Actuator/SLO 结构已存在。

**必须诚实的边界**

- 数据库 hash chain 若没有外部锚定，DBA 仍可整体重写，不能叫不可抵赖审计。
- 当前没有经过验证的多 AZ 主备、RTO/RPO、KMS/HSM、MFA、mTLS、集群级 kill switch。
- 监控类定义了指标不等于生产路径已经完整埋点，更不等于 SLO 已达成。

---

## 6. K 线模块专项：最值得重点讲的技术难点

### 6.1 协议语义不是一一对应

| 来源 | 闭合标记 | volume 重点 | 时间/日历重点 | 当前处理 |
|---|---|---|---|---|
| Binance Spot | `x=true` | base/quote volume 分开 | 可有 UTC 与 UTC+8 K 线；连接最长 24h | BigDecimal、forming/finalized |
| Binance USDⓈ-M | `x=true` | 合约产品不能套用 Spot 限流 | 2026-04-23 后 Kline 必须走 `/market` | normalizer 可用，但默认 WS 地址已过期 |
| OKX Spot | `confirm=1` | `vol` 为基础币、`volCcyQuote` 为计价币 | `1D` 与 `1Dutc` 日历不同 | 已按 Spot 语义映射 |
| OKX Derivatives | `confirm=1` | `vol` 是合约张数，`volCcy` 才是基础币 | business WS，REST 响应先后不代表数据新旧 | base/quote 映射正确，但 canonical 未保留 contractVolume |
| CoinGlass | 返回已完成历史 bucket | 只有 `volume_usd` 时不能伪造 base volume | provider 来源与底层 price exchange 是两层身份 | baseVolume=0、quoteVolume=USD；底层场所血缘仍不足 |

### 6.2 必须保留的时间字段

生产 canonical event 至少需要：

```text
barOpenTime / barCloseTime
venueEventTime
gatewayReceiveTime
normalizedTime / persistedTime
connectionGeneration
sequence/updateId（若上游提供）
schemaVersion / revision
```

当前 Binance normalizer 把 bar open time 写入 `exchangeTimestamp`，虽然 connector 已读到消息 `E`，却没有单独保留。因此当前不能用 `receivedTimestamp - exchangeTimestamp` 声称交易所到本机延迟；它计算的主要是“距本根 K 线开盘多久”。

同机阶段延迟应使用单调时钟测量：

```text
local_ingest_latency = canonicalPublishNano - socketReceiveNano
```

跨机器/交易所 event-time 延迟只有在时钟同步、打点位置和上游时间语义都明确后才有意义。

### 6.3 K 线断线回补状态机

推荐生产流程：

1. 持久化每个完整 series key 的 `lastFinalizedOpenTime`。
2. 连接使用 exponential backoff + jitter，并服从交易所连接/订阅限额。
3. 新连接建立后创建新的 `connectionGeneration`，旧连接 callback 全部 fencing。
4. 暂存 live 数据，先计算 `[lastFinalized + interval, latestCompletedBucket]` 缺口。
5. 按产品能力分页 REST 回补；Binance Spot/当前 USDⓈ-M 每页上限均按 1,000 配置，OKX history-candles 每页 300。
6. 标准化、按 openTime 排序、校验 `low <= open/close <= high`、价格/数量精度和时间网格。
7. 以业务键和 revision 做幂等写入；迟到旧版本不得覆盖新版本。
8. 将暂存 live 数据与 backfill 合并，确认连续后再解除 live barrier。
9. 周期性和交易所 REST 对账，统计 gap、duplicate、late revision、reconnect 和 repair duration。

### 6.4 K 线和订单簿恢复不能混为一谈

K 线通常按时间网格检测缺口；L2 order book 必须按 sequence 恢复。

以 Binance Spot 官方流程为例：先缓冲 diff，拉 REST snapshot，丢弃 `u <= lastUpdateId`，第一条事件必须覆盖 snapshot ID，之后逐条验证序列连续。出现 gap 应丢弃本地簿并重建，不能继续提供一个“看起来还在更新”的错误订单簿。

OKX 深度也要按 `prevSeqId/seqId` 判断连续性。自 2026-06-23 起，部分 JSON order-book channel 的 checksum 固定为 0，不能继续依赖旧 checksum 逻辑。

### 6.5 截至 2026-07-15 的官方硬限制

以下数字来自各产品当前官方文档。不同产品的限制不能混用，推送周期也不能当成业务系统延迟 SLA。

| 产品 | 连接/心跳 | 单连接控制限制 | K 线 REST/推送重点 |
|---|---|---|---|
| Binance Spot WS | 单连接最长 24h；server 每 20s ping，1min 内无对应 pong 断开；每 IP 5min 最多 300 次连接尝试 | 每秒最多 5 条 inbound message，ping/pong/订阅控制都计数；最多 1,024 streams | 1s Kline 约 1,000ms 更新，其他 interval 约 2,000ms；Spot Kline REST 权重 2，单页最多 1,000 |
| Binance USDⓈ-M WS | 单连接最长 24h；server 每 3min ping，10min 内无 pong 断开 | 每秒最多 10 条 inbound message；最多 1,024 streams | Kline 属于 `/market` routed endpoint；不能继续使用旧无路由 `/stream` |
| OKX V5 WS | 每 IP 每秒最多 3 次建连；空闲小于 30s 时应 ping 并等待 pong | 每连接 login/subscribe/unsubscribe 合计 480 次/小时 | market candles 40 req/2s/IP、history-candles 20 req/2s/IP；单页最多 300；`confirm=1` 才闭合 |
| CoinGlass Kline REST | API Key/套餐约束需按合同核验 | 按 provider 限流实现预算器 | Spot/Futures 历史单页最大 1,000；`volume_usd` 不能冒充 base volume |

Binance Spot 的 5 messages/s 与 USDⓈ-M 当前 10 messages/s 是两个产品口径；Binance Spot 的 20s/1min ping 规则也不能套到 USDⓈ-M 的 3min/10min。

### 6.6 当前两个 P0 兼容性问题

#### P0-1：Binance USDⓈ-M WS 默认地址已失效

当前配置仍为：

```text
wss://fstream.binance.com/stream
```

Binance 当前文档将 Kline 归入 `/market`；旧无路由 `/ws`、`/stream` 在 2026-04-23 后永久下线。必须迁移到 `/market/ws/...` 或 `/market/stream?streams=...`，并更新测试。

#### P0-2：Binance 历史分页 `limit=1500` 不兼容当前接口

当前 provider 对 Spot/Perpetual 共用 `MAX_LIMIT=1500`。官方 Spot `/api/v3/klines` 最大 1,000；当前 USDⓈ-M Kline 也应按产品 capability 使用 1,000。需要把 endpoint、limit、权重和时间语义配置为产品能力矩阵，禁止共享魔法常量。

面试时可以把这两个问题讲成“2026-07 技术审计发现并列为 P0”；修复和真实联调完成前不能说已解决。

### 6.7 数据质量的真实缺口

- 现有 duplicate key 漏掉 exchange 和 marketType，可能把不同市场同一分钟 K 线误判为重复。
- zero base volume 被统一判异常，但 CoinGlass 在只有 USD volume 时按正确语义将 base volume 置零，二者冲突。
- DataQualityChecker 尚未完整接入 ingestion 主链，管理端 data-quality 更接近覆盖率视图。
- 存储为 `DECIMAL(20,8)`；扩展低价高精度 token 时应以 instrument tickSize/stepSize 验证，必要时扩宽精度。

因此不能在面试中说“已建成生产级数据质量平台”，应说“已识别规则与模型冲突，下一步用 venue golden corpus 和 quarantine 链路收口”。

### 6.8 前端 K 线图的事实边界与建议实现

【代码事实】当前 `frontend` 使用 ECharts 展示回测资金曲线、模拟账户分析和 SLO 图，但仓库里没有 candlestick/K 线交易终端组件，也没有面向前端的历史 bars 查询 + 实时增量 API。因此当前可以说“开发了 K 线采集、标准化、缓存、回补和持久化模块”，不能说“已完成专业前端 K 线终端”。

如果要把它补成完整的全栈 K 线模块，建议链路为：

```text
GET /market-bars?seriesKey&from&to&limit&cursor
        → 返回按 openTime 升序的 finalized snapshot

WebSocket/SSE /market-bars/live
        → snapshotVersion + sequence + forming/finalized delta

React adapter
        → 按完整 series key 隔离
        → openTime upsert forming
        → finalized 覆盖同 key 并冻结/按 revision 修订
        → ECharts candlestick + volume + factor/strategy overlays
```

关键难点：

- **snapshot + delta 一致性**：先订阅并缓冲增量，再取带 version 的 snapshot，丢弃旧 delta 后按序合并，避免页面初始化窗口丢 bar。
- **增量更新**：不能每次 forming update 都重建全部 React state/全量 `setOption(notMerge=true)`；使用 ECharts 实例增量更新、`requestAnimationFrame` 节流和固定可视窗口。
- **历史分页**：向左拖动时按时间 cursor 拉取，不用 offset；并发请求需要 generation/request fencing，切换 symbol 后旧响应不能污染新图。
- **时间显示**：存储和协议统一 UTC；显示层按用户时区转换。UTC 日线与 UTC+8/交易日历日线必须明确标识，不能只改坐标文字。
- **价格精度**：来自 instrument metadata 的 tickSize/stepSize 决定小数位，不用 JavaScript `number` 重新计算资金；展示可转数值，业务计算保留 decimal string/定点数语义。
- **故障状态**：图表区分 LIVE、STALE、BACKFILLING、DEGRADED；断线期间不能让最后一根 bar 看起来仍在实时更新。

【验收目标示例】可用 100,000 根历史 bar 做分页场景，窗口内只渲染 2,000–5,000 根；以真实 forming 更新回放测量主线程 long task、帧率、内存和切换 symbol 的 stale-response 数。具体 FPS、交互延迟和内存门槛应在目标浏览器/机器上实测后写入简历。

---

## 7. 市面方案横向比较与选型回答

### 7.1 事件管线

| 方案 | 最适合解决 | 优点 | 关键边界 | 本项目选择 |
|---|---|---|---|---|
| 同步 InMemoryEventBus | 小规模单进程、确定性调试 | 简单、延迟低、调用链直观 | 慢订阅者阻塞、无持久化/重放/跨节点 | 当前小 universe 合理 |
| 有界分区队列 | 单机内隔离与背压 | 可按 series 保序、暴露 lag | 仍无跨机持久化 | 当前第一优先演进 |
| LMAX Disruptor | 单 JVM 低分配高性能 fan-out | ring buffer、依赖图、缓存友好 | 满载策略必须明确；不替代 durable log | 热路径可选，不是默认必需 |
| Kafka/Redpanda | durable log、重放、消费组、服务拆分 | 生态成熟、partition 内顺序 | EOS 不覆盖 MySQL/交易所；跨 partition 无全序 | 出现跨节点恢复和独立伸缩时引入 |
| Pulsar | 多租户、存储计算分离、Geo/tiered | namespace/tenant 能力强 | 运维复杂；Shared 不保序，Key_Shared 有额外约束 | 多区域/多租户成为核心需求时评估 |

Kafka 行情分区键至少应为：

```text
exchange | marketType | nativeInstrumentId | timeframe | calendar
```

订单命令则按 `accountId` 分区。不要用一个 key 同时解决行情顺序和资金顺序。

### 7.2 时序存储

| 存储 | 适合 | 不适合/陷阱 |
|---|---|---|
| MySQL/PostgreSQL | OMS、余额、持仓、账本、强一致元数据 | 超大规模历史扫描成本高 |
| TimescaleDB | 需要 PostgreSQL 事务/约束，同时按时间分 chunk | 运维仍是 PostgreSQL 体系；极限分析吞吐不是唯一目标 |
| ClickHouse | 大规模行情、因子、聚合和列式分析 | 主键不是唯一约束；ReplacingMergeTree 是后台最终去重，查询需处理合并前重复 |
| QuestDB | 时序、乱序写入、低延迟查询 | O3 写入有放大；去重和 WAL/UPSERT KEY 有明确限制 |
| 对象存储 + Parquet/Iceberg | 原始行情归档、低成本 replay、数据湖治理 | 不能作为在线 OMS 真相源 |

推荐分工：MySQL/PostgreSQL 保持订单、资金和账本真相源；行情热查询使用 ClickHouse/Timescale/QuestDB 之一；原始不可变数据进入对象存储。Redis 只做最新快照和缓存，不做唯一事实源。

### 7.3 交易协议与编码

| 方案 | 解决什么 | 不能误解成什么 |
|---|---|---|
| REST | 查询、低频命令、回补 | 不是实时有序事件流；超时不代表订单失败 |
| WebSocket | 实时行情和订单回报 | 不自动提供 durable replay 和 exactly-once |
| FIX 4.x | 机构订单/回报/Drop Copy、序列、心跳、重传 | 使用 FIX 不等于天然低延迟 |
| FIXP | 面向高性能会话、序列和恢复 | 仍需业务风控、持久化和幂等 |
| FAST | 有状态模板压缩行情 | 丢包后的字典恢复更复杂；不是消息队列 |
| SBE | 固定布局、低分配二进制编码 | 只是一种编码，不提供持久化/重传 |

若面试岗位是券商接入和机构交易，FIX/FIXP、Drop Copy、sequence reset、resend/gap fill、session schedule、comp ID、证书与专线比“用了 Kafka”更重要。若岗位是撮合核心，通常还会涉及 SBE/Aeron/Disruptor、CPU pinning、内存分配、journal 和 deterministic replay；当前 Spring/JSON 项目不要包装成该类系统。

---

## 8. OMS、执行和资金一致性：交易所面试官最常追问的场景

### 8.1 外部订单不能承诺端到端 exactly-once

正确答案：

```text
at-least-once delivery
+ stable clientOrderId
+ idempotent state transition
+ UNKNOWN state
+ private stream / REST query
+ periodic reconciliation
= effectively-once business effect and eventual convergence
```

Kafka producer idempotence只处理 producer retry；Kafka transaction 可以原子提交 Kafka output 和 consumer offset，但不能让交易所 HTTP、副作用数据库和 Kafka 自动成为一个事务。

### 8.2 cancel/fill race

典型时序：

```text
T1 本地发 cancel
T2 订单在交易所撮合
T3 本地收到 cancel ack 或 fill，二者网络顺序不确定
```

设计原则：

- `CANCEL_REQUESTED` 不是终态。
- 收到 fill 后按累计成交量 delta 更新，不能因为已请求撤单而丢弃。
- 最终状态可能是 `FILLED`、`PARTIALLY_FILLED + CANCELLED`，以交易所权威状态和成交为准。
- 每个外部 fill ID 幂等；余额/持仓更新与本地 fill 落库放在同一事务。
- 对账比较订单、成交、余额和持仓，不能只看 order status。

### 8.3 同账户并发和热点账户

当前模拟链路的行锁优先保证正确性。若热点账户吞吐成为瓶颈，采用 account-keyed single-writer/actor；不能简单去掉锁，也不能只依赖多线程。

### 8.4 双分录账本的价值

余额是当前快照，账本是可追溯变更历史。每个业务事务按资产校验借贷相等；对账时可从 ledger 反推余额并定位哪一笔业务破坏了守恒。生产还需不可变 journal、外部归档/签名锚定、受控补账和四眼审批。

### 8.5 风控模型需要主动承认的问题

当前 `PositionSizer` 最多按余额 30% 下单，而 `MaxLossPerTradeRule` 将订单总名义价值当成损失，上限为余额 2%。因此普通信号在默认参数下容易被过度拒绝；这不是成熟的 risk-at-stop 模型。

应改为：

```text
maxLoss = |entryPrice - stopPrice| × quantity × contractMultiplier
        + feeEstimate + slippageReserve
```

永续还需考虑杠杆、标记价格、维持保证金、资金费率、强平距离和保险缓冲。面试时把当前规则称为“保守名义敞口闸门”，不要称为完整单笔最大亏损模型。

---

## 9. 欧洲客户与券商/交易所场景

### 9.1 先判断客户角色，再谈适用规则

| 角色 | 主要关注 |
|---|---|
| 欧盟 Crypto-Asset Service Provider / crypto venue | MiCA；获得授权的 CASP 还进入 DORA 范围 |
| MiFID 投资公司/券商运行算法交易 | MiFID II Article 17、RTS 6、DORA |
| MiFID Trading Venue | RTS 7、业务时钟规则、DORA |
| 向金融机构卖软件的 ICT 供应商 | 不会仅因卖软件自动成为 CASP，但客户会把 DORA 的审计、数据地点、恢复、通知、退出和分包要求传导到合同 |

Crypto Spot、证券型 token、tokenized bond、crypto derivative 的法律分类可能不同。工程团队不能因为系统“像交易所”就自行断言全部法规当然适用，应由合规/法律按主体、产品和服务确认。以下内容是工程基线，不构成法律意见。

### 9.2 MiCA

- MiCA 的 ART/EMT 章节自 2024-06-30 适用，其余主要规则自 2024-12-30 适用。
- ESMA 2026-04 声明指出，各成员国过渡安排最迟于 2026-07-01 结束；当前日期不能再用“仍在最长过渡期”解释无授权服务。
- 对工程的直接含义包括容量、有序运行、错误订单控制、业务连续性、记录、客户资产隔离、市场滥用监测和可测试性。

### 9.3 DORA

- DORA 自 2025-01-17 适用。
- 要求 ICT 风险框架、依赖清单、事件处理、备份恢复、韧性测试、第三方登记和可执行退出计划。
- 重大 ICT 事件当前报告节奏：分类后尽快且最迟 4 小时、同时不晚于首次知悉后 24 小时；中报在初报后 72 小时内；终报在中报或最新更新后 1 个月内。
- DORA 没有给所有系统规定统一的 RTO/RPO。数值必须由业务关键性和影响分析决定并演练。

### 9.4 算法交易与交易场所容量

- RTS 6 面向运行算法交易的投资公司，要求上线/重大修改前测试、盘前控制、kill functionality、年度自评，并以过去 6 个月最高消息/交易量的 2 倍做相关压力能力准备。
- RTS 7 面向交易场所，强调系统容量、实时监控、throttle、BCP 和测试；容量基线与历史峰值相关，而不是任意宣布一个 TPS。
- 这些规则不因“是 crypto 项目”自动适用；但面对券商/交易所客户，可作为比互联网系统更严格的设计基线。

### 9.5 当前业务时钟规则不能再只背旧 RTS 25

Commission Delegated Regulation (EU) 2025/1155 已明确 repeal 2017/574，并在当前日期处于生效状态。面试时应引用当前规则和客户角色，不要只背旧法规编号。

工程上至少做到：

- 可追溯到 UTC，记录准确打点位置，至少年度复核。
- 对 gateway-to-gateway latency 大于 1ms 的相关场景，最大 UTC 偏差为 1ms、粒度 1ms 或更好。
- 对 latency 不高于 1ms 及 HFT 技术场景，最大偏差为 100µs，记录粒度 0.1µs 或更好。
- PTP/NTP 架构、holdover、clock drift、leap second、时钟源切换和告警都要纳入测试；仅保存 `System.currentTimeMillis()` 不够。

### 9.6 区域、身份和安全

- OKX 当前文档对 EEA 注册用户要求使用相应区域 API domain，机构交付不能把全球域名硬编码为唯一入口。
- 交易和用户数据要做区域部署、保留/删除、访问审计与数据分类；市场公开数据和个人/订单数据不能使用同一治理策略。
- 生产需要 mTLS、OIDC/MFA、RBAC/ABAC、KMS/HSM、密钥轮换、最小权限、四眼审批和 break-glass 流程。

---

## 10. 可重复的压测与验收方案

### 10.1 环境清单

报告首页必须固定：CPU 型号/核数、内存、磁盘、NIC、JDK 和 GC、容器限制、实例数、数据库版本与参数、网络 RTT、消息副本/ack、payload 大小、是否包含持久化。

### 10.2 行情 corpus

- 从各 venue 保存脱敏 raw payload 和 schema version。
- 覆盖正常、缺字段、极端精度、forming/finalized、乱序、重复、旧连接回调、日历边界。
- 为每个交易所/市场建立 golden expected event，字段映射必须 100% 通过；坏数据进入 quarantine，不能静默跳过。

### 10.3 负载阶梯

```text
warm-up: 10 min
1× expected peak: 30 min
2× expected peak: 30 min
5× short burst: 5 min
24h soak
72h/7d long soak（候选发布版本）
```

证券/算法交易客户还需把监管要求的历史峰值倍数纳入正式验收，不用通用互联网压测替代。

### 10.4 故障注入

- WebSocket 5/30/120 分钟断流、half-open、pong 丢失、DNS 切换、旧连接迟到 callback。
- Binance 429/418/Retry-After，OKX 限流/5xx/空页/重复 cursor。
- MySQL 延迟、连接池耗尽、事务死锁、主从切换、磁盘满。
- 应用在“发送订单前”“交易所接受后、本地 ack 前”“本地提交后、outbox 标记前”三个窗口崩溃。
- 时钟漂移和时间源切换。

### 10.5 指标定义

```text
receive_to_canonical = canonicalPublishNano - socketReceiveNano
canonical_to_strategy = strategyStartNano - canonicalPublishNano
strategy_compute      = strategyEndNano - strategyStartNano
order_local_accept    = localDurableNano - orderRequestNano
venue_ack             = venueAckTime - sendTime
recovery_duration     = liveBarrierReleased - reconnectDetected
```

每项报告 p50/p95/p99/p99.9、样本量和时间窗口，并同时记录 throughput、drop、duplicate side effect、gap、late revision、queue lag、CPU、allocation、GC pause 和数据库延迟。

### 10.6 可用作下一阶段的验收门槛

以下是【验收目标示例】，不是当前结果：

- 注入 1,000,000 条事件，其中 10% 重复、最大 2 秒乱序；finalized 存储行数必须等于 unique key 数，重复策略副作用为 0。
- 模拟 5/30/120 分钟断流；完成 backfill 后 active series 的 finalized 时间网格完整率为 100%，再解除 live barrier。
- 在定义的 2×峰值负载下 drop=0；任何 overflow 必须显式 fail/degrade 并告警，禁止静默丢弃。
- 24h/7d soak 后 watermark 单调、无线程/内存持续增长、无无法解释 gap。
- 外部订单超时注入后不产生重复 venue order；所有 UNKNOWN 在约定窗口内被对账或进入人工处置队列。

恢复 P95/P99、延迟和可用性数值必须结合客户 SLA、交易所限流和实测基线再填写。

---

## 11. 高压追问与严谨回答

### Q1：你说高并发，实际 TPS 是多少？

> 当前默认只有 12 条 1m 序列，闭合写入平均 0.2 条/秒；574 个默认测试也排除了 stress/soak/external。我不会把架构设计说成生产 TPS。扩容模型是 6,000 条序列，forming 压测输入 6,000 events/s，但那是发生器场景；正式数字要附机器、payload、持续时间、P99、drop 和持久化口径。

### Q2：第一个瓶颈在哪里？

> 当前更可能先卡在同步消费和数据库往返：EventBus 同步，线程池满会 CallerRuns，100 条 batch 实际循环单条 upsert。第一步是执行器隔离、真正 batch 和 lag 指标，不是盲目增加线程。

### Q3：为什么没一开始用 Kafka？

> 当前 universe 很小，Kafka 会增加部署和故障面。进程内总线适合快速验证和确定性调试；当需要 durable replay、跨实例 fan-out、消费者独立扩缩和审计保留时再引入。Kafka 是恢复与解耦方案，不是为了简历上的中间件名字。

### Q4：Kafka 分区键怎么选？

> 行情按完整 series identity，保证同一市场序列在同一 partition；订单按 accountId，保证同一资金账户 single-writer。Kafka 只有 partition 内顺序，不能说全局有序。

### Q5：实现 exactly-once 了吗？

> 没有端到端 exactly-once。行情是可能重复投递加自然键幂等；本地模拟交易在单数据库事务内实现一次生效；实盘通过 clientOrderId、UNKNOWN、查单、私有流和对账收敛。Kafka EOS 也不覆盖交易所副作用。

### Q6：Outbox 是否解决 exactly-once？

> 它解决业务事务与事件记录的双写原子性。publisher 在发送后、标记前崩溃仍会重复，所以消费者必须幂等。当前仓库也没有已接入 Kafka 的 handler，不能说已完成可靠投递。

### Q7：如何处理重复、乱序和修订？

> 当前自然键解决存储重复，forming/finalized 分开；但策略前缺少完整幂等和 reorder 门。生产要加 venueEventTime、connectionGeneration、sequence/revision、watermark 和迟到数据策略，旧修订不能覆盖新版本。

### Q8：怎么证明延迟？

> 当前 Binance 的 exchangeTimestamp 实际是 bar open time，不能作为网络延迟。我要分别记录 venue event、socket receive、normalize、strategy、submit 和 ack，并用同机 monotonic clock 与跨机 PTP/NTP。没有打点和校时就不报亚毫秒 P99。

### Q9：队列满怎么办？

> 不能用无界队列。按数据价值分别阻塞、降级、丢弃最新状态或 fail closed；finalized bar、订单和账本不可静默丢。当前 10,000 条行情缓冲满后拒绝是明确缺口，需要 durable WAL/MQ、DLQ 和 backlog 指标。

### Q10：HTTP 下单超时怎么办？

> 不能直接重试。发送前持久化稳定 clientOrderId，超时进入 UNKNOWN，通过订单查询和私有流确认。只有交易所明确不存在该订单后才允许受控重发。

### Q11：同账户并发如何防超卖？

> 当前用数据库行锁串行化同一账户，多账户并行。扩展时按 accountId single-writer，数据库版本与账本守恒继续作为最后防线。

### Q12：Kill switch 生产可用吗？

> 当前只是 JVM 内存状态，不满足多实例和重启恢复。生产要持久化并广播，分全局/账户/策略/venue/symbol 级，明确定义禁止新单、撤单和强平行为，并有审批、审计和演练。

### Q13：回测真实吗？

> 它能保证 bar 级 as-of、闭合 K 线、下一 bar 执行和成本假设可复现，适合策略研究。没有完整 L2 queue、交易所撮合规则和资金费率回放时，不能等同撮合仿真。

### Q14：574 个测试能证明生产可用吗？

> 不能。它们证明默认离线契约通过；压力、soak、external 默认排除，也没有完整 MySQL/Testcontainers、重启恢复和灾备证据。生产还需真实回放、并发事务、故障注入、24/72h soak 和主备演练。

### Q15：明天接券商/欧洲交易所，最先补什么？

> 权威 instrument master 与精度规则、实盘恢复和 UNKNOWN 扫描、FIX/Drop Copy 会话、账户 single-writer、集群 kill switch、UTC 时钟、mTLS/OIDC/MFA/KMS、不可篡改审计、主备与 RTO/RPO 验证，以及按 DORA/MiCA/MiFID 角色做数据和事件治理。

---

## 12. 面试红线

不要说：

- “我们实现了端到端 exactly-once。”
- “Kafka 单机百万 TPS，所以系统容量没问题。”
- “WebSocket 不会丢数据。”
- “用了 FIX 就是低延迟。”
- “P99 小于 1ms”，但拿不出打点位置、时钟、机器和样本。
- “零丢包”，但缓冲满后代码会拒绝数据。
- “审计不可篡改”，但只有同库 hash chain。
- “已经是交易所核心”，实际只有行情、策略、回测和模拟执行。
- “已满足欧洲监管”，但没有适用性意见、控制矩阵和验收证据。

推荐收口：

> 当前项目证明了我对行情正确性、策略确定性、OMS 状态、资金守恒和不确定状态恢复的系统性理解。它仍是单节点研究与模拟执行平台；扩容、机构协议和合规部分是基于现有代码做出的演进设计，我不会把它伪造成已经上线的生产业绩。

---

## 13. 当前代码的面试前整改优先级

### P0：影响当前功能正确性

1. 将 Binance USDⓈ-M Kline WS 迁移到 `/market` routed endpoint，并更新连接测试。
2. 将 Binance backfill 上限从共享 1,500 改为按产品 capability 的 1,000，并覆盖分页/限流测试。
3. 修正风险规则：按 stop distance、contract multiplier、费用和滑点计算 risk-at-stop。

### P1：影响生产化叙述

1. 增加 venueEventTime、socketReceiveTime、connectionGeneration、revision/schemaVersion。
2. 策略前增加 series 分区、bounded reorder 和 finalized idempotency gate。
3. 为 OKX/CoinGlass 加 stale callback fencing、pong deadline、持久化 checkpoint 和精确补洞。
4. 拆分线程池和有界队列；真正 JDBC/multi-values batch；缓冲满时不可静默丢 finalized 数据。
5. 修正 DataQualityChecker 的完整 key 与 source-aware volume 规则，并接入主链/quarantine。
6. 完成 live order 启动恢复、UNKNOWN 扫描、venue snapshot 对账、Outbox handler 和集群 kill switch。

### P2：机构化能力

1. 权威 instrument master：tick/step、合约乘数、交易状态、交易日历、公司行为。
2. FIX/FIXP、Drop Copy、sequence recovery、证书和会话日历。
3. Kafka/对象存储原始行情、ClickHouse/Timescale 历史查询与冷热分层。
4. PTP/NTP、mTLS、OIDC/MFA、KMS/HSM、外部审计锚定、多 AZ 灾备。

---

## 14. 一手资料

### 交易所

- [Binance USDⓈ-M WebSocket routed endpoint 迁移公告](https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/websocket-market-streams/Important-WebSocket-Change-Notice)
- [Binance USDⓈ-M WebSocket 连接规则](https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/websocket-market-streams/Connect)
- [Binance Spot WebSocket Streams](https://developers.binance.com/docs/binance-spot-api-docs/web-socket-streams)
- [Binance Spot REST API / Klines](https://github.com/binance/binance-spot-api-docs/blob/master/rest-api.md#klinecandlestick-data)
- [Binance Spot FIX API](https://github.com/binance/binance-spot-api-docs/blob/master/fix-api.md)
- [Binance Spot SBE Market Data](https://github.com/binance/binance-spot-api-docs/blob/master/sbe-market-data-streams.md)
- [OKX API V5](https://www.okx.com/docs-v5/en/)
- [OKX order-book checksum deprecation](https://www.okx.com/en-us/help/okx-order-book-channels-checksum-field-deprecation)

### 协议与中间件

- [FIX Session Layer](https://www.fixtrading.org/standards/fix-session-layer-online/)
- [FIXP](https://www.fixtrading.org/standards/fixp-online/)
- [Simple Binary Encoding](https://github.com/aeron-io/simple-binary-encoding)
- [Kafka Design](https://kafka.apache.org/41/design/design/)
- [Apache Pulsar Geo-replication](https://pulsar.apache.org/docs/4.0.x/administration-geo/)
- [LMAX Disruptor User Guide](https://lmax-exchange.github.io/disruptor/user-guide/)

### 欧洲法规与监管资料

- [MiCA — Regulation (EU) 2023/1114](https://eur-lex.europa.eu/eli/reg/2023/1114/oj?locale=en)
- [ESMA：MiCA 过渡期结束声明（2026-04）](https://www.esma.europa.eu/sites/default/files/2026-04/ESMA75-113276571-1679_Statement_on_the_end_of_transitional_periods_under_MiCA.pdf)
- [DORA — Regulation (EU) 2022/2554](https://eur-lex.europa.eu/eli/reg/2022/2554/oj)
- [DORA incident reporting timelines — Delegated Regulation (EU) 2025/301](https://eur-lex.europa.eu/eli/reg_del/2025/301/oj/eng)
- [RTS 6 — Delegated Regulation (EU) 2017/589](https://eur-lex.europa.eu/eli/reg_del/2017/589/oj/eng)
- [RTS 7 — Delegated Regulation (EU) 2017/584](https://eur-lex.europa.eu/eli/reg_del/2017/584/oj/eng)
- [当前业务时钟规则 — Delegated Regulation (EU) 2025/1155](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32025R1155)
- [ESMA 2026 Algorithmic Trading Supervisory Briefing](https://www.esma.europa.eu/sites/default/files/2026-02/ESMA74-1505669079-10311_Supervisory_Briefing_on_Algorithmic_Trading_in_the_EU.pdf)

---

## 15. 仓库证据索引

- 默认 universe 与旧 Binance perpetual 地址：`src/main/resources/application.yml:58-81`
- 默认测试排除组：`pom.xml:114-135`
- 同步事件总线：`src/main/java/com/tj/crypto/event/InMemoryEventBus.java`
- forming/finalized 缓存：`src/main/java/com/tj/crypto/factor/cache/InMemoryBarCache.java`
- Binance/OKX normalizer：`src/main/java/com/tj/crypto/marketdata/normalize/`
- 历史回补：`src/main/java/com/tj/crypto/marketdata/backfill/`
- K 线缓冲与拒绝：`src/main/java/com/tj/crypto/storage/service/MarketDataPersistenceService.java`
- 实际循环 upsert：`src/main/java/com/tj/crypto/storage/mapper/BarEventMapper.java`
- 策略分区锁：`src/main/java/com/tj/crypto/central/StrategyEngine.java`
- 模拟订单事务：`src/main/java/com/tj/crypto/trading/paper/PaperOrderService.java`
- 结算与双分录：`src/main/java/com/tj/crypto/trading/paper/PaperSettlementService.java`、`src/main/java/com/tj/crypto/trading/paper/ledger/DoubleEntryLedgerService.java`
- OMS/UNKNOWN：`src/main/java/com/tj/crypto/storage/service/OmsPersistenceService.java`
- 私有订单流：`src/main/java/com/tj/crypto/trading/venue/stream/VenuePrivateEventProcessor.java`
- 对账：`src/main/java/com/tj/crypto/trading/reconciliation/ReconciliationService.java`
- 实盘双层开关：`src/main/java/com/tj/crypto/trading/venue/LiveTradingWriteGuard.java`
