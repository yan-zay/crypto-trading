# 管理后台规划方案

## 目标

管理后台不是展示型网页，而是交易系统的控制台。它的核心职责是让市场数据、因子、策略、回测、模拟交易、风控、执行、持久化和可观测性都能被统一查看、配置、验证、发布、回滚和审计。

本系统后续所有可变参数都应逐步从硬编码和静态 `application.yml` 迁移到“版本化配置中心”。后台页面只是入口，真正重要的是后端配置模型、校验规则、生效流程和运行态一致性。

当前已经落地 React 管理控制台、认证/RBAC、总览、策略/因子、风控、信号、OMS/配置、单/多因子回测、持久异步回测任务、完整结果/研究质量报告、Trading Operations 和 Reliability & Audit。模拟交易页面已覆盖账户、下单/撤单、订单、成交、持仓/余额、权益、归因、TCA、账本和对账。本文件同时保留目标架构；未实现条目必须继续标为规划，不能根据页面存在就推断真实交易所认证或生产安全已经完成。

## 建设原则

- 先做配置治理，再做漂亮页面。没有版本、校验、审计和回滚的后台会放大交易风险。
- 回测、模拟交易、实盘执行必须尽量复用同一套策略、因子、风控、仓位、滑点和账户模型。
- 所有高风险操作必须可追踪：谁改了什么、为什么改、何时发布、影响哪些策略、是否可以回滚。
- UI 不直接写业务状态。UI 调用后端 API，后端通过领域服务完成校验、发布和事件通知。
- 密钥不落普通配置表，不在前端回显明文。后台只管理 secret 引用和连通性检测。
- 实盘适配 API 必须默认禁用；只有交易所认证、凭据治理、HA/fencing、长期演练和审批门禁全部通过后，后台才允许切换到真实资金写模式。

## 推荐技术栈

### 后端

- 继续使用 Java 17 + Spring Boot 3.5.x。
- 使用 Spring MVC REST API 暴露管理接口。
- 使用 Spring Validation 做参数校验。
- 使用 MyBatis-Plus 管理配置表、审计表和运行记录。
- 使用 Spring Actuator + Micrometer 作为可观测性基础。
- 使用 OpenAPI 文档生成接口契约，建议后续加入 `springdoc-openapi`。
- 实时状态推送优先用 Server-Sent Events，后续需要双向控制时再加 WebSocket。

### 前端

当前方案：React 19 + TypeScript 6 + Vite 8 + TanStack Query + Ant Design 6。权益曲线使用 Apache ECharts 6 按需加载。新增页面应延续该技术栈，不再并行引入 Vue 或另一套组件库。

交易控制台属于高密度运维型产品，视觉风格应克制、清晰、可扫描。主要使用表格、筛选器、抽屉表单、详情页、状态徽标、趋势图、K 线图和配置 diff，不做营销式首页。

图表库建议：

- K 线和交易标记：TradingView Lightweight Charts。
- 指标曲线、资金曲线、回撤曲线：Apache ECharts。
- 表格：前端组件库 Table，后续数据量大时引入虚拟滚动。

## 后端模块拆分

建议新增 `com.tj.crypto.admin` 包，不要把管理后台逻辑散落到交易核心模块。

```text
com.tj.crypto.admin
  api              REST Controller, SSE endpoint
  application      应用服务，编排校验、发布、回滚、运行态查询
  domain           管理后台领域模型，例如配置版本、发布单、审计记录
  dto              API 入参和出参
  mapper           管理后台表 Mapper
  service          配置仓库、运行态查询、审计服务
  validation       配置校验器和依赖校验
```

核心交易模块继续保留：

```text
com.tj.crypto.marketdata
com.tj.crypto.factor
com.tj.crypto.strategy
com.tj.crypto.backtest
com.tj.crypto.risk
com.tj.crypto.execution
com.tj.crypto.storage
com.tj.crypto.observability
```

后台通过应用服务调用这些模块，不反向依赖 UI 或 Controller。

## 核心页面与功能

### 1. 总览 Dashboard

目标：让用户一眼判断系统是否健康、是否有信号、是否有风险。

功能：

- 数据源连接状态：Binance、OKX、Coinglass；当前产品范围明确不接入其他平台。
- 事件吞吐：BarEvent、LiquidationEvent、FundingRateEvent、OpenInterestEvent 每分钟数量。
- 当前启用策略数量、最近信号数量、最近拒单数量。
- 模拟账户权益、持仓、今日盈亏、最大回撤。
- 风控状态：全局开关、kill switch、日亏损限制、单笔限制。
- 最近异常：连接断开、数据延迟、持久化失败、风控拒绝、策略异常。

后端 API：

- `GET /api/admin/overview`
- `GET /api/admin/metrics/realtime`
- `GET /api/admin/health/connectors`

### 2. 数据源与订阅管理

目标：管理交易所、市场类型、交易对、频道、时间周期和连接配置。

功能：

- 数据源列表：Binance、OKX、Coinglass。
- 连接配置：WS URL、REST URL、代理、重连间隔、订阅批大小。
- 订阅配置：symbol、marketType、channel、timeframe。
- 数据质量：最近事件时间、延迟、缺口、重复率、解析失败数。
- 手动操作：连接、断开、重连、测试订阅。

注意：

- API key 只能保存 secret 引用，例如 `COINGLASS_API_KEY`，不保存明文。
- 订阅配置应落库并版本化，避免继续在代码里硬编码 symbol 列表。
- Binance K 线服务当前仍未启用，后台第一阶段应先展示状态并提供配置，不直接贸然开启实盘连接。

后端 API：

- `GET /api/admin/connectors`
- `POST /api/admin/connectors/{id}/test`
- `POST /api/admin/connectors/{id}/connect`
- `POST /api/admin/connectors/{id}/disconnect`
- `GET /api/admin/subscriptions`
- `POST /api/admin/subscriptions/draft`
- `POST /api/admin/subscriptions/publish`

### 3. 交易标的管理

目标：统一管理交易对和市场元数据，避免散落在枚举、硬编码 List、配置文件中。

功能：

- 标的列表：exchange、marketType、symbol、baseAsset、quoteAsset、pricePrecision、quantityPrecision、minNotional、tickSize、lotSize。
- 启用状态：是否允许接收行情、是否允许回测、是否允许模拟交易、是否允许实盘。
- 标签：主流币、山寨币、meme、低流动性、高风险。
- 批量导入：从交易所 exchangeInfo 拉取。

必要性：

- 执行引擎需要 `minNotional`、精度、手续费、tickSize 才能判断订单是否可成交。
- 回测和模拟交易必须使用同一份交易规则，否则收益曲线不可信。

后端 API：

- `GET /api/admin/instruments`
- `POST /api/admin/instruments/sync`
- `PUT /api/admin/instruments/{id}`

### 4. 因子与指标管理

目标：让技术指标、衍生品指标和多因子组合可配置、可回测、可观察。

功能：

- 因子注册表：名称、类型、依赖事件、依赖缓存、参数、输出质量。
- 参数配置：SMA/EMA/RSI/MACD/Bollinger 等 period 参数。
- 衍生品因子：爆仓密度、资金费率变化、持仓量变化、基差、成交量异常。
- 因子预览：指定 symbol/timeframe，展示最近因子值和质量状态。
- 因子版本：同名因子不同参数形成不同版本，例如 `MACD_HIST@12_26_9`。
- 因子依赖图：策略依赖哪些因子，因子依赖哪些数据源。

配置要求：

- 因子配置不能只保存在 `application.yml`。后台配置应生成版本并可发布。
- 修改因子参数后，必须能触发回测验证。
- 因子计算失败应记录质量状态，不应静默返回 `null` 后无法追踪。

后端 API：

- `GET /api/admin/factors`
- `GET /api/admin/factors/{name}/preview`
- `POST /api/admin/factors/configs/draft`
- `POST /api/admin/factors/configs/{version}/publish`
- `POST /api/admin/factors/configs/{version}/rollback`

### 5. 策略管理

目标：管理策略注册、启停、参数、symbol 白名单、依赖因子、运行模式和发布流程。

功能：

- 策略列表：名称、版本、状态、监听事件、依赖因子、启用 symbol。
- 策略参数：阈值、置信度、冷却时间、持仓方向、最大并发仓位。
- 生命周期：草稿、已校验、已发布、运行中、暂停、归档。
- 运行模式：回测、模拟交易、实盘观察、实盘执行。
- 策略信号流：最近信号、原因、因子快照、是否被风控拒绝。
- 策略配置 diff：当前版本和待发布版本对比。

关键要求：

- `StrategyProperties` 必须真正驱动策略启停和 symbol 过滤。
- 策略阈值不应只靠 setter 在测试中设置。
- 策略生成的信号必须包含足够上下文，方便后台解释信号来源。

后端 API：

- `GET /api/admin/strategies`
- `GET /api/admin/strategies/{name}`
- `POST /api/admin/strategies/{name}/draft`
- `POST /api/admin/strategies/{name}/validate`
- `POST /api/admin/strategies/{name}/publish`
- `POST /api/admin/strategies/{name}/pause`
- `POST /api/admin/strategies/{name}/resume`

### 6. 回测实验室

目标：把“配置策略”与“验证策略”连起来。

功能：

- 创建回测任务：策略版本、因子版本、风险配置版本、执行模型版本、symbol、timeframe、时间范围、初始资金。
- 历史数据检查：覆盖率、缺口、异常值、数据源。
- 运行状态：排队、运行中、成功、失败、取消。
- 结果展示：收益率、年化、最大回撤、胜率、盈亏比、夏普、Sortino、Calmar、交易次数、手续费、滑点。
- 图表：资金曲线、回撤曲线、K 线买卖点、逐笔交易列表、月度收益热力图。
- 对比：多个回测结果横向比较，参数矩阵分析。

关键要求：

- 回测不能绕过执行引擎和风控。后续应重构为复用 `ExecutionEngine`、`RiskEngine`、`PositionSizer`、`SlippageModel`。
- 回测指标不能用极端哨兵值表示无亏损，例如 `Double.MAX_VALUE`。应使用 `null`、`INF` 枚举或可解释字段。
- 回测结果必须保存配置快照，否则无法复现。

后端 API：

- `POST /api/admin/backtests`
- `GET /api/admin/backtests`
- `GET /api/admin/backtests/{runId}`
- `POST /api/admin/backtests/{runId}/cancel`
- `GET /api/admin/backtests/{runId}/trades`
- `GET /api/admin/backtests/{runId}/equity-curve`

### 7. 模拟交易控制台

目标：用实时行情验证策略，但不触发真实下单。

功能：

- 模拟账户创建、启动、暂停、停止、重置。
- 当前余额、权益、未实现盈亏、已实现盈亏、持仓列表。
- 模拟成交、风控拒绝、策略信号时间线。
- 模拟交易配置版本绑定。

关键要求：

- 模拟交易也必须复用执行和风控链路。
- 停止模拟交易时应记录最终账户快照。
- 不能只保存在内存，否则后台刷新后状态不可恢复。

后端 API：

- `POST /api/admin/paper/accounts`
- `POST /api/admin/paper/accounts/{id}/start`
- `POST /api/admin/paper/accounts/{id}/pause`
- `POST /api/admin/paper/accounts/{id}/stop`
- `GET /api/admin/paper/accounts/{id}`
- `GET /api/admin/paper/accounts/{id}/orders`
- `GET /api/admin/paper/accounts/{id}/positions`

### 8. 风控与执行管理

目标：集中管理风险规则、仓位模型、滑点模型、手续费模型和 kill switch。

功能：

- 风控规则列表：单笔最大亏损、每日最大亏损、最大持仓、最大杠杆、最大回撤、最大连续亏损。
- 仓位模型：固定比例、波动率目标、Kelly fraction、ATR 止损模型。
- 执行模型：市价、限价、滑点 bps、手续费、成交失败模拟。
- 全局 kill switch：停止新开仓、只允许平仓、停止所有策略。
- 风控模拟：输入订单和账户状态，解释为什么通过或拒绝。

关键要求：

- `PositionSizer` 不应硬编码 30%，应读取版本化风险配置。
- `MaxDailyLossRule` 不应依赖系统当前时间，应使用交易事件时间和交易日历。
- 多头、空头、保证金、资金费率、手续费、冻结资金、权益计算要在账户模型中明确。

后端 API：

- `GET /api/admin/risk/configs`
- `POST /api/admin/risk/configs/draft`
- `POST /api/admin/risk/configs/{version}/publish`
- `POST /api/admin/risk/simulate`
- `POST /api/admin/risk/kill-switch`

### 9. 信号、订单、成交与持仓

目标：把策略输出和执行结果串起来，形成可解释链路。

功能：

- 信号列表：策略、symbol、类型、置信度、原因、因子快照。
- 订单列表：状态、拒绝原因、预期价格、成交价格、滑点。
- 成交列表：成交数量、价格、手续费、PnL。
- 持仓列表：方向、数量、入场价、当前价、未实现盈亏、保证金占用。
- 链路追踪：MarketEvent -> Factor -> Signal -> RiskCheck -> Order -> Trade。

后端 API：

- `GET /api/admin/signals`
- `GET /api/admin/orders`
- `GET /api/admin/trades`
- `GET /api/admin/positions`
- `GET /api/admin/traces/{traceId}`

### 10. 可观测性与告警

目标：确保系统不是黑盒。

功能：

- 数据延迟、事件吞吐、异常计数。
- 策略耗时、因子计算耗时、执行耗时。
- 数据库写入延迟、批量 flush 状态。
- 连接重连次数、最近断开原因。
- 告警规则：数据中断、策略异常、日亏损触发、持仓异常、订单拒绝激增。

建议：

- 接入 Spring Boot Actuator。
- 后续使用 Prometheus + Grafana。
- 日志增加 traceId 或 eventId，方便从事件追踪到信号和订单。

后端 API：

- `GET /api/admin/observability/metrics`
- `GET /api/admin/observability/logs`
- `GET /api/admin/alerts`
- `POST /api/admin/alerts/rules`

### 11. 配置版本、发布和审计

这是管理后台的核心基础设施。

配置生命周期：

```text
Draft -> Validated -> Published -> Active -> RolledBack / Archived
```

每次发布必须记录：

- 配置类型：connector、subscription、factor、strategy、risk、execution。
- 配置版本。
- 配置 JSON 快照。
- 修改人。
- 修改原因。
- 校验结果。
- 发布时间。
- 回滚来源。

建议数据表：

```text
admin_config_version
admin_config_publish_log
admin_audit_log
admin_runtime_state
admin_connector_config
admin_subscription_config
admin_instrument_config
admin_factor_config
admin_strategy_config
admin_risk_config
admin_execution_config
admin_backtest_run
admin_backtest_trade
admin_backtest_equity_point
admin_paper_account
admin_paper_order
admin_paper_position
admin_paper_trade
```

最小字段建议：

```text
id
config_type
config_key
version
status
content_json
checksum
created_by
created_time
published_by
published_time
rollback_from_version
remark
```

## API 设计约定

- 后台 API 统一前缀：`/api/admin`。
- 所有写操作必须有 requestId，防止重复提交。
- 所有发布操作必须传 remark。
- 所有配置保存前必须做结构校验和业务校验。
- 所有高风险操作返回影响范围预览，例如影响哪些策略、哪些 symbol、哪些运行中的模拟账户。
- 返回值使用统一响应结构，但不要吞掉错误细节。

建议响应结构：

```json
{
  "success": true,
  "data": {},
  "errorCode": null,
  "message": null,
  "traceId": "..."
}
```

## 权限设计

第一阶段可以只做单用户或本地开发鉴权，但接口设计要预留权限模型。

建议权限：

- `VIEWER`：只读。
- `RESEARCHER`：创建回测和查看结果。
- `OPERATOR`：启动、暂停模拟交易。
- `RISK_MANAGER`：发布风控配置和 kill switch。
- `ADMIN`：管理用户、权限和所有配置。

高风险操作：

- 发布实盘策略配置。
- 开启实盘执行。
- 调整日亏损和最大持仓限制。
- 关闭 kill switch。
- 删除历史数据或回测记录。

这些操作后续应支持二次确认和双人审批。

## 前端信息架构

建议左侧导航：

```text
总览
数据源
交易标的
因子指标
策略
回测实验室
模拟交易
风控执行
信号订单
可观测性
配置版本
审计日志
系统设置
```

页面风格：

- 顶部显示当前环境、运行模式、全局风险状态。
- 列表页支持筛选、排序、导出。
- 配置编辑使用抽屉或独立编辑页。
- 发布前展示 diff、影响范围和校验结果。
- 图表区优先展示真实数据，不使用装饰性图形。

## 分阶段实施计划

### Phase A：只读控制台和配置盘点

目标：不改变交易行为，只展示当前系统状态。

任务：

- 新增 `/api/admin/overview`。
- 新增数据源状态、策略列表、因子列表、风险配置读取接口。
- 前端搭建基础布局和只读页面。
- 在 `CLAUDE.md` 引用本文档。
- 添加 Controller、Service、DTO 单元测试。

验收：

- 不依赖 Coinglass API key 也能打开后台。
- 不依赖 Binance 连接也能展示“未连接/未启用”状态。
- `mvn test` 通过。

### Phase B：版本化配置中心

目标：配置可保存、校验、发布、回滚。

任务：

- 设计配置版本表和审计表。
- 实现 factor、strategy、risk 三类配置的 draft/validate/publish/rollback。
- 策略运行态读取 Active 配置。
- 增加配置 diff 页面。

验收：

- 修改策略阈值后，不改代码即可生效。
- 禁用策略后，策略引擎不再执行该策略。
- 发布记录可回滚。

### Phase C：回测实验室

目标：后台可创建回测并查看完整报告。

任务：

- 持久化回测任务和结果。
- 增加资金曲线、回撤曲线、逐笔交易。
- 回测复用执行和风控链路。
- 支持多个配置版本横向对比。

验收：

- 每个回测结果可复现。
- 回测报告能展示配置快照。
- 指标计算没有不可解释的极端哨兵值。

### Phase D：模拟交易控制台

目标：后台控制模拟账户，使用实时行情验证策略。

任务：

- 模拟账户持久化。
- 模拟订单、成交、持仓持久化。
- 启停模拟交易。
- 展示实时权益和持仓。

验收：

- 服务重启后能恢复模拟账户状态。
- 模拟交易复用 `ExecutionEngine` 和 `RiskEngine`。

### Phase E：风控执行与实盘前置

目标：建立实盘前的安全边界。

任务：

- kill switch。
- 风控模拟器。
- 订单链路追踪。
- 权限和审计。
- 实盘执行接口预留，但默认禁用。

验收：

- 无权限不能修改风险配置。
- 开启实盘前必须通过回测、模拟、风控校验。
- 所有高风险操作都有审计记录。

## 当前实现边界与下一优先级

已解决的旧阻断：默认测试恢复为绿灯；Binance/OKX/Coinglass BTC/ETH 双市场数据、OMS journal、持久模拟账户/账本、重启恢复、reconciliation、outbox、异步回测、研究质量、执行容量、审计 hash chain、SLO 和对应管理页面已经落地。完整模拟交易链已在隔离 MySQL 与实际 HTTP API 上验收，并由 Playwright 覆盖页面关键路径。

下一优先级：

- 用 Binance testnet/OKX demo 凭据执行私有 API 契约矩阵和受限 canary；当前适配器存在不等于已认证。
- 对危险修复、实盘开关和风险放宽增加影响预览、二次确认、双人审批与 MFA。
- 实现单写者 fencing、跨实例 job/订单 ownership、备份恢复和 disaster kill switch。
- 把 secret 引用接到 Vault/KMS/HSM，加入轮换/撤销、IP 白名单和最小权限检查。
- 增强 reconciliation incident 工单流：owner、SLA、证据附件、审批 repair 和通知升级。
- 增加 connector/data-quality run chart、SLO burn-rate 告警、分布式 trace 和可执行 runbook。
- 研究 UI 后续增加 sweep DAG、参数稳定性图、PBO/DSR、K 线成交标注和 artifact retention。
- 优化 ECharts/Ant Design 大 chunk，补移动端、键盘可达性和大数据表虚拟滚动。

## 给另一个开发 AI 的提示词

```text
你现在接手 crypto-trading 项目的下一阶段管理后台建设。请先阅读：
1. CLAUDE.md
2. docs/crypto-trading-system-blueprint.md
3. docs/architecture/admin-management-console.md
4. docs/architecture/event-model.md
5. docs/architecture/strategy-engine-v2.md
6. docs/architecture/backtest-engine.md
7. docs/architecture/multi-factor-backtesting.md
8. docs/architecture/oms-ems.md
9. docs/architecture/risk-engine.md
10. docs/architecture/execution-engine.md

开发要求：
- 先进入计划模式，列出任务拆分、影响文件、测试策略和回滚风险，再开始编码。
- 面向文档编程。每次新增或修改功能，都必须同步更新设计文档，说明为什么这样设计、优点、能力边界、暂不支持内容。
- 不要把后台逻辑写进现有核心交易类。新增管理后台代码优先放在 com.tj.crypto.admin 下。
- 不要重复造轮子。前端优先使用成熟组件库，后端优先使用 Spring MVC、Validation、MyBatis-Plus、Actuator、OpenAPI。
- 不要写超长类。Controller 只做入参出参，业务编排放 Application Service，领域校验放 validation/domain。
- 所有可变配置都要考虑版本、校验、发布、回滚、审计。
- 以实际代码、Flyway schema 和测试为准；不要根据旧完成报告重复实现或声称能力完整。
- 当前只允许 Binance/Coinglass/OKX、SPOT/PERPETUAL、BTCUSDT/ETHUSDT，不添加其他平台或币种。
- 不要把“已有 Binance/OKX private adapter”写成“已可实盘”。没有 owner 凭据认证、HA/fencing、Vault/KMS、DR 和审批门禁时必须保持 live write guard 关闭。
- 每次改动后运行 mvn test。涉及启动逻辑时还要运行一次 jar 或 spring-boot:run 做启动验证。
- 前端改动运行 npm run lint、npm run build 和相关 Playwright E2E。
- 添加必要的单元、集成和 E2E 测试，尤其是任务恢复、取消竞态、配置发布、权限和后台 API 校验。

优先实施“交易所认证与生产门禁”，不要重复开发已完成的异步任务、模拟账本或审计/SLO：
1. 盘点 `TradingVenueGateway`、write guard、OMS recovery 和 reconciliation 的当前契约，先写认证矩阵与失败语义。
2. 只使用 Binance testnet/OKX demo 或明确授权的小额账户；密钥从环境/Vault 注入，日志和测试证据必须脱敏。
3. 覆盖 place/query/cancel、部分成交、重复 clientOrderId、超时未知结果、用户流断线、listen key/登录续期、限流和时钟偏差。
4. 对 spot/perpetual、BTC/ETH、账户模式、position side 和 reduce-only 分别验证，禁止用一个成功样例外推全部组合。
5. 将 venue truth 与 OMS order/fill/position/balance 对账，未知结果不得自动重下单。
6. 增加单写者 fencing、审批门禁和 canary notional 上限；故障时 fail closed，并保留只减仓/撤单通道。
7. 跑全量后端、前端、E2E、重启恢复和 24h soak，输出可复核证据、未验证边界、回滚步骤与 runbook。
```
