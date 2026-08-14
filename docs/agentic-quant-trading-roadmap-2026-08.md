# Agent 有界自治量化交易系统实施计划（2026-08）

> 状态：执行中的主路线图。本文定义方向、优先级和验收门槛，不代表其中所有事项已经完成。
>
> 适用范围：当前 `crypto-trading` 仓库，以及由它演进出的研究、模拟和受控交易系统。
>
> 最近更新：2026-08-14。P0 执行清单见 `docs/p0-acceptance-runbook.md`。

## 1. 执行摘要

当前系统已经具备事件驱动行情、point-in-time 因子、策略信号、回测、持久化模拟交易、OMS、受保护私有交易所适配、管理后台和确定性的 Agent L0 只读查询层。它适合继续做研究与模拟验证，但尚不能被认定为可安全投入真实资金的生产系统，也尚未接入可推理的 LLM Agent 控制面。

最终目标不是让 LLM 持有交易所密钥并自由决定每笔订单，而是建设：

> **Agent 辅助、策略受审、权限受限、确定性内核执行的有界自治量化研究与交易平台。**

Agent 负责非确定性的研究、实验编排、解释和运维辅助；Java 交易内核独占账户真相、组合约束、硬风控、OMS、签名、执行、对账和 Kill Switch。任何 Agent 失败、超时、越权或被注入时，系统必须默认拒绝交易，而不是绕过确定性控制。

在法律边界、可靠性证据和策略样本外证据完成前：

- 真实资金结论为 **NO-GO**；
- `live-write-enabled` 和交易所级写开关保持关闭；
- 不新增“Agent 直连交易所”能力；
- 优先完成 P0 事实链和一个黄金策略认证流程。

## 2. 本路线图的状态语义

本文使用以下状态，避免把代码存在、测试通过和生产验收混为一谈：

| 状态 | 含义 |
|---|---|
| `设计` | 已有方案，但尚无实现或证据 |
| `已实现待验收` | 代码和本地测试存在，但缺少目标环境或持续运行证据 |
| `已验收` | 所有列出的验收门槛均有可审计证据 |
| `外部阻塞` | 依赖法律意见、账户、凭据、基础设施、时间窗口或独立审批 |

仅有类、接口、页面、单元测试或 mock E2E，不等于生产能力已经验收。

## 3. 2026-08 审计基线

### 3.1 已有基础

- Binance、Coinglass、OKX 的公共行情连接器、标准化事件、回补和数据质量基础。
- 约 14 个因子、6 个配置策略、策略管理和事件路由。
- 回测、组合回测、walk-forward、成本模型、结果持久化和异步任务。
- 持久化模拟账户、订单/成交/持仓、部分成交、资金费、强平和双分录账本。
- OMS 状态机、订单 journal、幂等 `clientOrderId`、Binance/OKX 私有适配基础。
- 风控规则、Kill Switch、审计链、Outbox、SLO 和管理控制台的基础实现。

### 3.2 关键缺口

| 领域 | 当前缺口 | 风险 |
|---|---|---|
| 构建与发布 | 审计时工作树含大量未提交变化；默认 Maven 曾使用 JDK 8；CI、profile、端口和数据库初始化路径不完全一致 | 无法证明从干净 clone 可复现 |
| 策略发现 | 审计时配置声明 6 个策略，但只有 2 个是 Spring Bean | 运行事实与配置/界面不一致 |
| 行情 | 缺 trade tick、L2、mark/index 等完整真相源；异步持久化缓冲满时可能丢数据 | 回测真实性和故障恢复不足 |
| 研究 | 样本数据和可审计 Alpha 证据不足；缺 purged/embargo、PBO、Deflated Sharpe 和多重检验控制 | 过拟合及选择偏差不可量化 |
| 实盘闭环 | 策略信号只自动进入模拟盘；实盘以管理 API 调用为主；缺完整启动恢复和交易所真相对账 | 订单、成交、持仓可能长期不一致 |
| 风控 | 实盘前置风控覆盖有限；Kill Switch 持久性/集群一致性不足 | 重启或故障切换可能解除保护 |
| 可靠性 | Outbox 缺实际 handler 时仍可能确认发布；缺外部告警、单写者 fencing、HA/DR 证据 | 丢事件、重复下单或静默故障 |
| 安全 | 缺 KMS/Vault、MFA、双人审批、细粒度实盘角色和凭据轮换演练 | 高影响写操作权限过大 |
| Agent | 无模型接入、工具权限模型、沙盒、评测和 prompt-injection 防护 | 不具备安全引入 Agent 的条件 |

### 3.3 本轮实施状态（截至 2026-08-14）

`已实现待验收`：

- [x] 形成并在 `CLAUDE.md`、`AGENTS.md` 引用本实施计划；
- [x] JDK 17/Maven Wrapper 门禁、CI、SBOM、安全扫描、严格 smoke 与真实后端浏览器路径；
- [x] 6 个配置策略全部注册，缺失和重名 fail-fast；
- [x] Outbox 无 handler 不再确认发布，业务事件审计 handler 与幂等 checkpoint 同事务；
- [x] 闭合行情 fsync spool/replay，异常、乱序和运行期 gap 在策略前拒绝并触发 HALT；
- [x] Kill Switch 持久化、启动默认 HALT、单写者数据库租约/fencing、非终态实盘订单启动/周期恢复；
- [x] Kill Switch 跨节点周期收敛、版本化拒绝陈旧降级；恢复未完成/UNKNOWN/积压时保持 HALT；
- [x] 实盘初始命令 INSERT-only；V14 以 `account + exchange` 行锁将 OMS 初始事实和 gross-notional 预算原子预占，`UNKNOWN` 保留预算，终态才释放；
- [x] 交易所真相 capability gate：已知订单恢复与账户级订单/成交/余额/持仓真相必须双 READY；能力缺失、查询失败或差异触发 `BLOCKED + HALT`；
- [x] `RESEARCH_ONLY` 部署硬门、Sandbox endpoint allowlist、独立且不继承运维/风控权限的 `LIVE_TRADER`、JSON 登录、外部 webhook 告警；
- [x] MySQL/Testcontainers fresh migration + paper/账本/对账黄金路径（本机 Docker 不可用，等待 CI 真实执行）；
- [x] purged/embargo、Deflated Sharpe 和 CSCV/PBO 统计工具；
- [x] 不可变数据 manifest/SHA-256 校验、完整试验族预登记和黄金策略本地认证流水线；报告强制 `alphaCertified=false`；
- [x] Agent L0 固定白名单只读研究查询，无模型、凭据和交易写工具。

`外部阻塞/时间验收进行中`：专项法律意见、KMS/MFA/双人审批真实设施、owner testnet/demo 合同测试、备份恢复与 24h/7d chaos/soak、不可变真实基准数据的 Alpha 证据、30～90 天 forward paper/shadow。P0 和短期目标因此仍不得整体标成“已验收”。

`仍属内部 NO-GO 的架构边界`：交易所 API 不接收数据库 fencing token，租约不能单独数学上排除旧进程在最后一次校验后恢复发送；capability gate 已实现，但 Binance/OKX 的全量活动订单、近期成交以及内部账户级持久投影仍明确为 `UNSUPPORTED`，因此当前必然阻断开仓；日损失、回撤、策略/方向预算和 loss-at-stop 尚未进入同一原子预占模型。任何一项未关闭时，代码存在也不能标记为 P0-4/P0-5 已验收。

### 3.4 本轮本地验证快照（2026-08-14）

- JDK 17 / Maven Wrapper 3.9.9：`./mvnw clean verify -B` 运行 716 项测试，0 failure、0 error、4 skipped；4 项均为本机 Docker 不可用时明确跳过的 MySQL/Testcontainers 用例。随后针对研究模式门禁作用域新增回归测试，相关 12 项聚焦测试通过；下一次全量基线应为 717 项。
- MySQL fresh migration、V11→V14 upgrade、paper/账本/对账黄金路径和风险预占并发用例已进入测试；CI guard 在 `CI=true` 且 Docker 不可用时直接失败，当前本机结果不冒充真实容器验收。
- 相同输入连续两次构建：JAR SHA-256 均为 `D270C9B9A99D102FB6737E6A156824BA8537508A5AF570412A38063E6EFCAFCA`；SBOM SHA-256 均为 `79971C01676FD766006ADB48AF5B5E14AE325E42430F2966B218450E717E7310`。
- 前端 production dependency audit 为 0 漏洞；lint、production build 和 8 项 Playwright mock-contract E2E 通过。真实 Spring/MySQL E2E 已写入 CI，但尚无远端 workflow 运行证据。
- GitHub workflow/application YAML 严格拒绝重复键并解析通过；POM/JSON、bash、PowerShell、Compose 静态检查和 `git diff --check` 通过；Actions 均固定到 40 位 commit SHA。

因此，本轮可准确表述为：**P0/短期的仓库内工程实现已形成可测试基线，但 P0 和短期阶段退出条件尚未整体验收，真实资金继续 NO-GO。**

## 4. P0：真实资金前置阻断项

P0 的目标是让系统的事实链可复现、可停止、可恢复、可对账。P0 完成仍不自动授权真实资金；它只是进入长期模拟、交易所 demo/testnet 和独立验收的必要条件。

### P0-0 法律、主体和产品边界

交付物：

- 明确运营主体、开发/部署地点、服务器、账户归属、目标用户和服务模式；
- 获得适用司法辖区的专项法律意见；
- 明确自营研究、软件工具、信号服务、代客交易等边界；
- 法律结论进入发布审批，默认拒绝未知或不允许的场景。

验收门槛：法律意见有日期、范围、主体和批准人；产品与基础设施实际部署和意见一致。若任何要素面向中国大陆，继续保持虚拟货币实盘写入冻结，直到专项意见明确允许。

参考：中国人民银行等部门 2026 年《关于进一步防范和处置虚拟货币等相关风险的通知》：<https://www.pbc.gov.cn/tiaofasi/144941/3581332/2026020619591971323/index.html>。

### P0-1 可复现仓库、构建和 CI

交付物：

- 审查并拆分当前未提交变化，建立可回滚 release tag；
- 固定 Java 17 工具链，并提供 Maven Wrapper 或等价的版本约束；
- 从干净 clone 执行后端测试、前端 lint/build、真实后端 E2E；
- 使用临时 MySQL/Testcontainers 验证 Flyway 全新安装与逐版本升级；
- 修复 CI/容器 health check 端口、profile、假绿脚本和重复迁移入口；
- 加入依赖、SAST、secret、SBOM 和镜像扫描。

验收门槛：受保护分支上的同一提交可重复构建；失败检查不能返回成功；全新库和升级库均通过；产物记录源码、依赖、JDK 和容器 digest。

### P0-2 配置与运行事实一致

交付物：

- 配置声明启用的策略、因子、连接器和 handler 必须真实存在；
- 未注册、重名、未知或不兼容配置必须在启动/发布时 fail-fast；
- Admin 状态来自运行时 registry，不以静态配置冒充运行事实。

验收门槛：默认配置中的 6 个策略均被 Spring 发现；启用但缺 Bean 及重名策略有负向测试；配置发布和回滚保持相同约束。

### P0-3 事件和行情不丢失

交付物：

- Outbox 只有在明确 handler 成功后才能确认发布；零 handler 和未知事件类型 fail-closed；
- 行情缓冲使用有界背压、持久 spool/replay 或等价机制，禁止静默丢弃；
- gap detection、原始事件留存、checksum 和重放修复形成闭环；
- 时钟偏差、行情新鲜度和形成中/已完成 K 线隔离进入 readiness。

验收门槛：断网、数据库超时、消费者崩溃和缓冲耗尽测试证明不静默丢失；所有重放保持幂等；无法保证数据完整时自动禁止新开仓。

### P0-4 持久化硬风控和单写者

交付物：

- Kill Switch 持久化、重启保持、集群一致，并有独立于主应用的触发路径；
- 建立账户、策略、标的、方向、单笔、总暴露、日损失、回撤和 loss-at-stop 预算；
- 建立数据库租约/fencing token，任何时刻只有一个有效订单写者；
- 减仓/撤单通道与开仓权限分离，风险数据不新鲜时 fail-closed。

验收门槛：重启、双实例、网络分区和租约过期测试中不产生重复订单；所有声明限制有边界/性质测试；Kill Switch 触发后没有新的增加风险订单。

### P0-5 交易所真相对账和恢复

交付物：

- 私有用户流账户刷新真正触发订单、成交、余额和持仓快照；
- 启动时和周期性扫描 `UNKNOWN`、活动订单及缺失成交；
- OMS、交易所订单、成交、余额、持仓和内部账本持续收敛；
- 自动修复严格幂等；高影响修复需审批并完整审计；
- 策略信号继续与实盘执行解耦，直到所有晋级门槛通过。

验收门槛：丢 ACK、延迟/重复成交、断线、进程崩溃和重启测试证明最终收敛且不重复下单；未解决差异自动冻结风险增加操作。

### P0-6 安全、可观测性与灾备

交付物：

- 凭据进入 Vault/KMS/HSM 或等价托管；无提款权限，按账户/标的/额度最小授权并绑定 IP；
- 实盘角色、MFA、双人审批、短期 capability token、撤销和轮换演练；
- 外部告警路由、值班手册、结构化日志、指标、trace 和 readiness/liveness；
- 加密备份、PITR、恢复演练、RPO/RTO、审计链外部锚定；
- 24 小时、7 天 soak 以及网络/交易所/数据库 chaos。

验收门槛：没有高危扫描项；轮换/撤销、恢复和故障演练有可审计报告；无人值守告警能到达责任人；7 天运行无未解释数据缺口、账实差异或无界资源增长。

### P0-7 黄金策略证据与晋级门

交付物：

- 选择一个简单、可解释的黄金策略，不先扩展更多策略；
- 使用带 manifest/checksum 的不可变真实数据；
- 完成 purged + embargo、nested walk-forward、PBO、Deflated Sharpe、多重检验登记；
- 纳入真实手续费、资金费、滑点、延迟、部分成交、容量和基准；
- 相同事件输入通过 backtest、paper/shadow parity；
- 进行至少 30～90 天 forward paper/shadow 验证。

验收门槛：预先登记的样本外、风险、容量和稳定性阈值全部通过；失败实验也被记录；任何人工排除和参数变更可追溯。参考 Deflated Sharpe 方法：<https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2460551>。

## 5. 下一步最优先的执行顺序

只选一个当前 Epic 时，应选择 **“P0 可复现交易黄金路径”**，而不是“增加 Agent”或“增加策略”。推荐顺序：

1. 完成 P0-0 边界决策，并在部署层硬冻结实盘写入；
2. 完成 P0-1，使所有后续证据绑定到可复现提交和真实数据库测试；
3. 完成 P0-2/P0-3，消除配置幻觉、Outbox 假发布和行情静默丢失；
4. 完成 P0-4/P0-5，使风控、单写者、恢复和交易所真相对账闭环；
5. 完成 P0-6，取得安全、灾备和持续故障运行证据；
6. 并行建设 P0-7，但不得跳过前述工程门槛连接真实资金；
7. 最后才进入 Agent L0/L1 和交易所 demo/testnet。

## 6. 短期目标（0～3 个月）

目标：从“功能丰富的研究/模拟系统”达到“事实一致、可恢复、可验证的研究与 paper 平台”。

交付：

- 完成 P0-0 至 P0-5 的本地和隔离环境实现；
- 干净 clone 的 JDK 17 构建、真实 MySQL/Flyway 集成测试和非 mock 关键 E2E；
- 修复配置发现、Outbox、背压、Kill Switch、单写者和对账恢复；
- 建立不可变 BTC/ETH 单场所基准数据集和黄金策略认证流水线；
- Agent L0：只读查询、研究摘要和报告生成，不具备写工具；
- 启动 30～90 天 paper/shadow 观察窗口。

阶段退出条件：

- 构建和数据库从零可复现；
- 故障注入下没有静默丢数据和重复订单；
- 账实差异能发现、冻结、修复并审计；
- backtest/paper 差异全部可解释并在预设容差内；
- Agent 不能访问凭据、私有交易 API 或配置写接口；
- 观察窗口若尚未走完，短期目标只能标记为“实施完成、时间验收进行中”。

## 7. 中期目标（3～12 个月）

目标：形成完整的策略生命周期、数据血缘和隔离 Agent 研究平台。

交付：

- trades、L2、mark/index、funding、OI 和原始事件对象存储/Parquet 数据层；
- 研究试验 registry、不可变数据/代码/容器/配置版本和失败实验登记；
- 策略 Draft → Review → Approve → Shadow → Canary → Promote/Rollback 状态机；
- 组合预算、相关性、压力情景、容量和 TCA；
- 交易所 demo/testnet 合同测试、速率限制/断线语料和私有流长稳验证；
- KMS/MFA/双人审批、外部告警、PITR、RPO/RTO 和 7 天 chaos/soak；
- Agent L1：在沙盒中生成假设和实验；Agent L2：仅操作 paper/shadow；
- 建立 prompt injection、越权、数据投毒、工具误用、复现率、成本和延迟评测集。

阶段退出条件：策略晋级完全由签名制品和 policy gate 驱动；Agent 每次模型、prompt、上下文、工具调用和审批均可追溯；测试网/模拟环境持续运行无重大未解释对账事件。

## 8. 长期目标（12～24+ 个月）

目标：仅在合法、证据充分的边界内建设小资金、有上限、可回退的自治交易。

交付：

- 资产类别无关的交易内核，可按许可接入合规券商或交易场所；
- 多账户/子账户、venue adapter、组合资本分配和版本化风险政策；
- shadow/champion-challenger、极小资金 canary、自动降级和人工接管；
- Agent L3 生成实盘建议并由人批准；满足全部门槛后才考虑 L4 有界自治；
- L4 只能在预批准策略、账户、标的、时段、最大名义金额、日损失和 TTL 内选择或调整，不得修改其自身边界；
- 永不建设可自由提款、改权限、改硬风控、在线自修改并自行晋级的 L5。

阶段退出条件：专项法律意见仍有效；至少跨越多个市场状态的样本外和 shadow/live 证据成立；每个订单可追溯；可随时停止、恢复、回滚和人工接管。

## 9. Agent 与确定性内核的边界

### 9.1 研究证据为何不支持“LLM 端到端直接管钱”

- NIST/CAISI 的 AgentDojo 实验中，面向目标模型优化的新攻击把最强攻击成功率从 11% 提高到 81%；2026 年 13 个前沿模型、25 万余次攻击的竞赛中，每个目标模型都至少出现一次成功劫持。因此，模型本身不是资金边界，外部确定性授权和隔离才是边界：[NIST 2025](https://www.nist.gov/news-events/news/2025/01/technical-blog-strengthening-ai-agent-hijacking-evaluations)、[NIST 2026](https://www.nist.gov/blogs/caisi-research-blog/insights-ai-agent-security-large-scale-red-teaming-competition)。
- 2026 年对 Agentic Trading 文献的审计在 19 项闭环研究中只找到 2 项可提取的时间一致切分、1 项明确交易成本、1 项说明 universe/生存偏差，且没有研究达到最高复现等级。这说明当前论文结果不足以替代本项目自己的 point-in-time、成本、偏差和复现门槛：[Agentic Trading](https://arxiv.org/abs/2605.19337)。
- KTD-Fin 在控制模型历史记忆泄漏后发现，累计收益大多可由被动市场和风格暴露解释，持续选股 Alpha 证据有限；因此每个 Agent 结果必须同时对 beta、风格、成本和简单基准做归因：[KTD-Fin](https://arxiv.org/abs/2605.28359)。
- EMNLP 2024 的 CryptoTrade 优于时间序列基线，但未优于传统交易信号；这支持把 LLM 用在研究、解释和编排层，而不是预设其交易决策天然优于简单规则：[CryptoTrade](https://aclanthology.org/2024.emnlp-main.63/)。

以上证据不证明 Agent 永远不能产生增量价值；它证明增量必须在泄漏受控、扣除成本、对照简单基线且可复现的实验中单独证实，并且即使证实，也不能让概率模型持有密钥或绕过硬风控。

### 9.2 目标架构

```text
非确定性 Agent 控制面
  研究资料/公告 -> 假设 -> 沙盒代码或策略 DSL -> 实验编排 -> 报告/晋级建议
                                  |
                                  v
确定性 Policy/Promotion Gate
  签名制品 + 数据/代码/模型版本 + OOS 门槛 + 权限 + 审批 + TTL
                                  |
                                  v
确定性 Java 交易内核
  目标仓位 -> 组合预算 -> 硬风控 -> OMS -> Signer/Executor -> Venue
       ^                                                  |
       +---------- 成交/持仓/余额/账本持续对账 <----------+

独立 Watchdog/Kill Switch 可冻结系统，不依赖 Agent 判断。
```

### Agent 可以做

- 读取经过授权和防投毒的数据、公告和运行指标；
- 提出可证伪的因子/策略假设；
- 在网络、凭据和生产系统隔离的沙盒运行实验；
- 生成代码变更或策略制品供审查；
- 分析故障、生成 runbook 建议和人类可读解释；
- 在 paper/shadow 中使用有范围、可撤销、短 TTL 的能力令牌。

### Agent 不可以做

- 查看交易所 secret、签名密钥、提款凭据或数据库管理凭据；
- 直接调用交易所私有 gateway；
- 绕过 Policy Gate、OMS、风险预算、单写者或 Kill Switch；
- 修改自身权限、资本上限、日损失、批准记录或审计日志；
- 把网页、邮件、新闻或模型输出中的自然语言直接转换成订单；
- 作为唯一的实时风险监控或唯一的停机触发器。

参考：OpenAI 建议按工具写入性、可逆性、权限和财务影响设置护栏与人工升级：<https://openai.com/business/guides-and-resources/a-practical-guide-to-building-ai-agents/>；NIST 指出 Agent 系统面临间接 prompt injection、数据投毒和 specification gaming 等风险：<https://www.nist.gov/news-events/news/2026/01/caisi-issues-request-information-about-securing-ai-agent-systems>。

## 10. 统一晋级门槛

任何从 Research → Paper → Shadow → Live Canary 的晋级，都必须同时满足：

| 门槛 | 最低证据 |
|---|---|
| 法律 | 主体、用户、部署、账户和服务模式在书面意见范围内 |
| 可复现 | Git、依赖、容器、数据 manifest、配置、seed 和模型/prompt 版本齐全 |
| 研究 | 预登记 OOS、PBO/DSR、多重检验、成本、容量和基准通过 |
| 一致性 | Backtest/Paper/Shadow 的订单、成交和 PnL 差异可解释 |
| 风控 | 所有硬限制、Kill Switch、单写者和减仓路径的负向测试通过 |
| 对账 | 订单、成交、余额、持仓、账本能在故障与重启后收敛 |
| 安全 | 最小权限、密钥托管、MFA、双人审批、轮换/撤销和无高危发现 |
| 运维 | 外部告警、runbook、备份恢复、RPO/RTO、24h/7d soak 和 chaos 通过 |
| Agent | 越权/注入/投毒评测通过；无 secret；所有工具调用可审计；可立即撤销 |
| 资本 | 隔离子账户、无提款、硬名义金额/日损失/TTL；从极小比例逐级放量 |

任一门槛失败或证据过期，自动降级到上一阶段；不能通过人工口头确认绕过。

## 11. 不能在代码提交时宣称完成的事项

以下工作依赖外部主体、真实环境或持续时间，代码和 mock 测试不能代替验收：

- 专项法律意见和监管/交易所许可；
- Binance/OKX 的 owner testnet/demo 凭据合同认证；
- KMS/Vault/HSM、IP 白名单、MFA 和双人审批的真实基础设施；
- 备份/PITR、异地恢复、RPO/RTO 和审计链外部锚定；
- 24 小时、7 天以及 30～90 天 paper/shadow 的完整观察窗口；
- 在不可变真实数据和未见市场上成立的 Alpha；
- 小资金 live canary、真实成交质量和多个市场状态下的稳定性；
- 值班人员、告警到达、事故演练和独立审批的组织证据。

这些事项必须记录开始/结束时间、环境、账户范围、负责人、失败和原始证据。尚未满足时只能写“未验证”或“进行中”。

## 12. 成功标准

系统的最终成功不是承诺固定年化收益，而是同时做到：

- 扣除完整成本后，存在可复现的样本外增量价值；
- Agent 相比无 Agent 基线带来可测量增量，而非只增加复杂度；
- 零未授权资金操作，零绕过硬风控的订单；
- 每个订单可追溯到数据、策略、代码、模型、配置、政策和审批版本；
- 账实差异可自动发现、冻结、收敛和审计；
- 系统可停止、可恢复、可回滚、可降级、可人工接管；
- 法律、账户和产品边界始终处于允许范围。
