# 长期有界自治目标仓库实施与验收说明（2026-08）

> 本文对应 `docs/agentic-quant-trading-roadmap-2026-08.md` 第 8 节，记录长期目标的仓库内安全契约、运行接线状态、验证入口和不能由代码替代的外部/时间证据。
>
> 基线日期：2026-08-24。
>
> 强制结论：本轮只形成了长期有界自治的确定性原语、不可执行契约和 fail-closed 验收入口。12～24+ 个月长期目标不能在一次代码变更中整体宣称完成；Agent L3/L4、资产类别无关执行闭环、watchdog/canary 资金面 applier、V23/V24 mapper/OMS 接线均为 `NOT_WIRED`，所有真实环境与持续时间项目均为 `NOT_VERIFIED`，L5 永不建设。系统继续保持 **RESEARCH_ONLY / NO-GO**。

## 1. 状态语义

| 状态 | 本文中的精确定义 | 能否据此开启真实资金 |
|---|---|---|
| `仓库内安全契约已实现待验收` | 类型、纯确定性决策、迁移或自动化测试入口存在；尚未证明生产接线、真实信任根或目标环境运行 | 否 |
| `NOT_WIRED` | 独立原语存在，但没有连接可信事实 producer、持久 current head、OMS 原子事务、最终 gateway、生产 IdP/KMS、CI 发布门或运行 applier | 否 |
| `NOT_VERIFIED` | owner、账户、凭据、法律意见、真实制品、外部签名、多个市场状态或持续时间证据不存在、过期或未可信验证 | 否 |
| `外部阻塞/时间验收` | 依赖仓库外主体、设施、真实账户、组织流程或不能用加速时钟压缩的观察窗口 | 否 |
| `永不建设` | 安全边界明确禁止，不是 backlog，也没有未来晋级路径 | 不适用 |
| `已验收` | P0、短期、中期和长期全部仓库、目标环境、外部与持续时间门槛均有同一候选版本的可审计证据 | 仍不能直接下单；必须由独立 policy/OMS/gateway 再授权 |

本地构造的 `GO`、Ed25519 测试密钥、fake verifier、mock 账户、固定时钟和 parser 测试只能证明契约行为，不能将 `NOT_WIRED` 或 `NOT_VERIFIED` 改写成“已验收”。任何 readiness decision、capital envelope、execution candidate、Agent receipt 或 lineage envelope 都不是交易权限。

## 2. 当前总览

| 长期交付 | 仓库内状态 | 运行接线状态 | 外部/时间状态 |
|---|---|---|---|
| 1. 资产类别无关资本与风险政策 | 通用金额/数量/标的规格、账户/子账户拓扑、签名政策及只减不增的紧急修订已实现待验收 | 持久 active head、PIT 估值、FX、OMS 原子预算与现有执行链为 `NOT_WIRED` | 合规资产目录、券商/venue 规则、真实 KMS 与多币种估值未验证 |
| 2. 目标仓位执行规划和实盘硬门 | 不可派单的确定性 planner 与现有 live fail-closed 加固已实现待验收 | signer/executor、可信 PIT producer、OMS reservation 和真实 adapter 未接；现有 live 仍是加密专用 | owner venue、真实合约经济条款和交易日历未验证 |
| 3. 长期统一晋级门 | L3/L4 分级、12 类 typed evidence、精确 subject/envelope 和失效语义已实现待验收 | 生产 evidence source/verifier、生命周期和最终写门均未接；默认拒绝 | 全部控制证据为 `NOT_VERIFIED` |
| 4. Champion/challenger 与小资金 canary | 内容寻址指标、固定资本阶梯、逐级审批、降级/回滚/HALT 决策已实现待验收 | 生产指标 producer、durable state owner、原子 applier 和资本预算未接 | 真实 30 天窗口、多个市场状态及小资金结果未验证 |
| 5. 独立 watchdog 与人工接管 | fail-closed 状态机、权威观察、持久 head/CAS 端口、分步双控恢复与人工接管契约已实现待验收 | 独立进程、生产 verifier、Kill Switch/OMS/告警/Agent 撤销 applier 未接 | 故障、接管、恢复、值班和长期运行演练未验证 |
| 6. Agent L3/L4 | 不可执行 L3 建议、完整内容寻址 L4 policy、单次 capability/use 绑定及默认拒绝端口已实现待验收 | 无真实模型、controller、可信身份、持久 store、OMS 或 gateway consumer；L3/L4 均 `NOT_WIRED` | 模型红队、真实 IdP/MFA、独立审批、增量价值与长期稳定性未验证 |
| 7. V23/V24 与每订单血缘 | 不可变控制面 schema、订单决策 envelope、OMS 可选精确绑定及 dispatch/reconciliation evidence 已实现待验收 | V23/V24 mapper/service、现有 signal/OMS 写路径、可信 verifier/store/admission 均未接 | 真实 MySQL fresh/upgrade、并发/故障和逐单证据未验证 |
| 8. L5 | 枚举、服务和 schema 均不提供 L5；架构契约禁止 secret/gateway/自我扩权 | 永久不接线 | **永不建设** |

准确表述是：**长期仓库安全契约与确定性模型已形成，但长期交易闭环没有完成。**

## 3. 交付一：资产类别无关的资本与风险政策原语

### 3.1 代码证据

- `src/main/java/com/tj/crypto/autonomy/capital/`：`AssetClass`、`AssetUnit`、`Money`、`QuantityUnit`、`Quantity`、`Venue`、`Account`、`Subaccount`；
- `InstrumentKey` 与版本化、内容寻址的 `InstrumentSpecification`：绑定 quantity unit、price domain、quote/settlement unit、contract multiplier、valuation convention/version；
- `CapitalRiskPolicy`、`RiskScope`、`RiskLimits`、`CapitalPolicyKernel`、`CapitalPolicyTrustRoot`、`ActiveCapitalPolicyResolver`；
- `CapitalEnvelopeResolution.executionAuthorized()` 固定为 `false`；解析结果不是 OMS reservation 或执行 permit；
- 紧急修订必须是连续版本、绑定 predecessor，且只能缩小资本、额度、scope 或有效期，不能扩权。

### 3.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=CapitalPolicyKernelTest,CapitalRiskPolicyValidationTest,EmergencyPolicyReductionTest" test
```

### 3.3 边界

- 该包是资产类别无关的资本**控制面原语**，不是已经完成的资产类别无关交易内核；
- `InstrumentValuation` 绑定估值约定，但不自行实现跨币种、inverse/quanto、期权、债券或企业行动估值；
- `ActiveCapitalPolicyResolver` 没有生产持久实现；active head、撤销、CAS、崩溃恢复均为 `NOT_WIRED`；
- 现有 backtest/paper/live/OMS 仍有加密市场、裸 `BigDecimal`、`quantity × price` 和单 venue/account 假设，不能以新值对象的存在冒充已迁移；
- 资本 envelope 尚未与组合、父账户、子账户、策略、方向、日损、loss-at-stop、`UNKNOWN` 预算在 OMS 同一事务中原子预占。

## 4. 交付二：不可派单执行规划与现有实盘 P0 fail-closed 加固

### 4.1 不可派单规划器

`src/main/java/com/tj/crypto/autonomy/execution/` 提供：

- `DeterministicExecutionPlanner`：把完整 target position、PIT 持仓/估值、资本 envelope、策略 release、政策摘要和 venue capability 编译为内容寻址候选；
- `TargetPositionPlanningRequest`、`PositionValuationSnapshot`、`ExecutionPlan`、`ExecutionCandidate`；
- 最大候选 TTL 为 120 秒，拒绝过期/不完整/近似 scope、跨零翻仓、未授权 short/reduce-only、交易时段关闭、单位或资本限制不匹配；
- `ExecutionPlan.dispatchAuthorized()` 与 `ExecutionCandidate.dispatchAuthorized()` 固定为 `false`；
- `VenueExecutionCapabilityVerifier` 默认拒绝；`IsolatedExecutionSignerPort`、`IsolatedVenueExecutorPort` 只有 availability seam，没有签名或派单 API。

验证入口：

```powershell
.\mvnw.cmd "-Dtest=DeterministicExecutionPlannerTest" test
```

### 4.2 当前实盘路径的 P0 fail-closed 修复

- `LiveTradingWriteGuard` 在最终 mutation 边界重新读取持久 Kill Switch、策略 release、治理 subject、证据集合和单次 permit；静态写开关或凭据存在不再构成授权；
- `LiveOrderService` 要求 writer lease、recovery readiness、受治理的 OMS/风险原子 reservation，并在发网前再次验证；模糊 HTTP/transport 结果保持 `UNKNOWN`，不自动重发或释放预算；
- LIVE_CANARY 的风险增加 MARKET 请求不能使用调用方自报 reference price；缺可信服务端估值时拒绝；
- 非单位 contract multiplier、OKX perpetual 及非明确 linear quote-settled 的开仓估值在完整单位模型接线前拒绝；已验证 reduce-only 路径保持可用；
- `AdminController` 的普通 API 只允许收紧 Kill Switch，拒绝单人切回 `NORMAL` 或 deactivate；恢复必须等待持久双控工作流；
- OKX SPOT MARKET 明确使用 base-asset quantity；instrument metadata 版本比较纳入 base/quote/settle asset。

这些修复只是在现有 crypto live 路径上消除已知 fail-open；它们不等于真实资金可用，也不等于支持其他资产类别。

### 4.3 仍未接线

- 可信 PIT position/valuation producer、跨币种 FX、inverse/quanto 和交易日历；
- 多账户/子账户身份贯穿 credential、gateway、私有流、OMS、fill、ledger、lease 与 risk reservation；
- 独立不可导出 KMS/HSM signer、真实 broker/venue adapter、原子 OMS/资本/日损预占和成交 checkpoint；
- planner candidate 到 exact OMS command、V24 envelope 和最终 gateway permit 的生产链。

## 5. 交付三：长期统一 Readiness Gate

### 5.1 代码证据

`src/main/java/com/tj/crypto/autonomy/readiness/` 提供：

- `LongTermReadinessGate.evaluateL3/evaluateL4/evaluateBoth`；
- `LongTermReadinessSubject` 精确绑定 subject、release、artifact、账户、环境、policy 和 `AutonomyEnvelope`；
- `AutonomyEnvelope` 绑定 venue、market type、标的集合、时段、计价币种、最大名义、最大日损和不超过 2 分钟的 capability TTL；
- `LongTermControl.values()` 的 12 项控制全部强制；新增枚举项会自动成为必需项；
- evidence reference 只携带 `evidence://` 指针和声明摘要，不能自报通过；外部 verifier 必须获取不可变 bytes、重算摘要、校验 schema/audience、签名、issuer/key 和撤销状态；
- L4 必须绑定同一 subject 的有效 L3 `GO`；L3/L4 证据不能跨级复用；
- `LongTermReadinessDecision` 不可由外部直接构造，`GO` 仍只是只读 readiness 事实，不是订单授权。

验证入口：

```powershell
.\mvnw.cmd "-Dtest=LongTermReadinessGateTest" test
```

### 5.2 边界

- 生产 `ReadinessEvidenceSource` 和 `ExternalReadinessEvidenceVerifier` 均未接，默认构造器永久 `NO_GO`；
- 单元测试中的 trusted verifier 是 policy fixture，不证明真实 artifact fetch、摘要重算、签名、issuer/key 撤销或外部信任根；
- readiness 尚未连接策略生命周期、Agent 服务、OMS、live gateway 或即时撤销 head；消费方未来仍需重新评估，不能缓存 `GO` 当权限；
- P0/短期/中期尚未整体验收时，长期门无论仓库测试结果如何都不能成为阶段退出依据。

## 6. 交付四：Champion/Challenger 与有上限 Canary

### 6.1 代码证据

`src/main/java/com/tj/crypto/autonomy/canary/` 提供：

- 内容寻址 `DeploymentScope`、`CanaryMetricSnapshot`、`CanaryRunState`、`CanaryDecision`；
- 收益、成本、回撤、TCA、对账事件、样本数和四类市场状态覆盖；完整性从原始事实派生，不能由调用方设置 PASS；
- 保守 v1 最低评估窗口 30 天、至少 1,000 个样本，并要求各市场状态覆盖；
- 固定资本阶梯 `1 → 5 → 10 → 25 bps`，只允许相邻一级晋升且每级要求精确 scope 的外部签名批准；
- 缺失或过期证据触发 HOLD/降级/HALT，未解释对账事件触发 HALT，收益/回撤恶化触发 rollback，成本/TCA 恶化触发降级；
- 决策只描述 `HOLD / ENTER_TINY_CANARY / ADVANCE / DOWNGRADE / ROLLBACK / HALT`，没有执行副作用。

验证入口：

```powershell
.\mvnw.cmd "-Dtest=CanaryDecisionEngineTest" test
```

### 6.2 边界

- 生产指标 producer、manifest/receipt verifier、durable run-state head 和原子 decision applier 均为 `NOT_WIRED`；
- `evaluate → apply` 之间的 CAS、release 切换、资本预算、Kill Switch 和审计同事务尚未实现；
- 30 天固定时钟测试不是实际 30 天，1/5/10/25 bps 决策也不证明任何真实资金已经分配；
- 真实 champion/challenger、多个市场状态、小资金成交质量和自动回退效果均为 `NOT_VERIFIED`。

## 7. 交付五：独立 Watchdog、人工接管与分步恢复

### 7.1 代码证据

`src/main/java/com/tj/crypto/autonomy/operations/` 提供：

- `DeterministicOperationsWatchdog`、`OperationsSnapshot`、`OperationsDecision` 和声明式 `OperationsActionIntent`；
- 启动默认 HALTED；未知、篡改、过期、错误来源、序列冲突、时钟异常或不可信观察均 fail-closed；
- 权威事实包括市场、OMS、对账、持久化、venue、Kill Switch 与 Agent heartbeat；Agent heartbeat 只能收紧，不能恢复；
- `DurableOperationsHeadPort` / current-head 校验和 CAS 契约用于拒绝旧快照重放与并发分叉；
- 人工接管需要独立 verifier；HALTED/MANUAL 只能先恢复到 DEGRADED，再以新的双控轮次恢复 NORMAL；
- 安全收紧 intent 与 snapshot/revision/TTL 绑定；状态机本身不调用 OMS、venue 或 Kill Switch。

验证入口：

```powershell
.\mvnw.cmd "-Dtest=DeterministicOperationsWatchdogTest" test
```

### 7.2 边界

- durable head、观察/审批/recovery verifier 均只有端口，没有生产实现；
- watchdog 尚未作为独立进程部署，也没有原子驱动 Kill Switch、禁用 Agent、触发对账、告警或人工接管；
- V23 watchdog/takeover 表与 Java 状态机之间没有 mapper/service；
- fake verifier 和固定时钟测试不证明真实 MFA、anti-replay、双控、值班到达或恢复演练。

## 8. 交付六：Agent L3 建议与 L4 有界契约

### 8.1 L3

- `TradeProposal` 是类型化、完整内容寻址且不可执行的建议；绑定环境/治理 subject、Agent/model/prompt/context/tool policy/trace/eval/data lineage、策略 release/assignment、账户、venue、标的规格、方向、政策和 TTL；
- `L3LiveAdviceService` 只接受 `L3_LIVE_ADVICE`，默认最大 TTL 5 分钟，要求独立认证的人类逐建议审批、单次 token 和可撤销 proposal；
- `ApprovedLiveAdvice` 是不透明审计回执，不是 order、command 或 gateway capability。

### 8.2 L4

- `BoundedAutonomyPolicy` 完整内容寻址，精确绑定允许的策略/账户/venue/标的组合、动作、交易窗口、最大名义、日损、loss-at-stop、proposal/capability TTL 和治理 subject；Agent 无法扩大集合或修改边界；
- `DailyLossSnapshot` 和 `CapabilityBudgetReservation` 绑定账户、交易日、币种、已实现损失、pending/UNKNOWN 预算、来源 manifest 和完整性；
- `L4BoundedAutonomyService` 只签发短 TTL、单次、不透明 capability；默认 verifier、authorization store、revocation store 和 capability store 全部拒绝；
- `CapabilityUseRequest` 将 use ID 派生自完整 exact command、execution candidate、valuation、risk budget/reservation、decision envelope、operator/consumer、proposal、policy、token 和 TTL；旧自由字符串 API永久 fail-closed；
- `CapabilityConsumption` 只是不可执行审计回执；Agent 包无 OMS、gateway、secret、Signer、Kill Switch 或配置发布依赖。

验证入口：

```powershell
.\mvnw.cmd "-Dtest=TradeProposalContentAddressingTest,L3LiveAdviceServiceTest,L4BoundedAutonomyServiceTest,AutonomyArchitectureContractTest" test
```

### 8.3 边界

- 当前没有真实模型 adapter、真实认证主体/mTLS workload、IdP/MFA、生产 verifier 或 durable store；
- L3 没有人工工作台/消费者，L4 没有 OMS/gateway consumer；二者均为 `NOT_WIRED / NOT_VERIFIED`；
- proposal 中的金额、loss-at-stop 或行情不能成为可信风控事实；生产接线必须由确定性内核从可信 PIT facts 重算；
- capability、累计资本/日损预占、OMS 初始事实、V24 lineage 和审计尚未在数据库同一事务中提交；
- 现有 `com.tj.crypto.research.agent` 的 L1/L2 枚举和工具白名单保持不含 LIVE，不通过给原包添加 L3/L4 来绕过隔离。

## 9. 交付七：V23/V24 与每订单不可变血缘

### 9.1 V23 有界自治控制面

`src/main/resources/db/migration/V23__create_bounded_autonomy_control_plane.sql`：

- 11 张不可变表覆盖账户/子账户 scope、版本化资本/风险政策、L3 proposal、人类审批、全局 replay reservation、L4 capability 及终态、canary、watchdog 和人工接管；
- UPDATE/DELETE 防篡改触发器、lowercase SHA-256、精确 FK/UNIQUE/CHECK；
- fresh schema 不插入任何 capability；L4 capability 必须受审批、政策、金额、标的集合和 TTL 约束；
- 文件头明确标记 `NOT_WIRED schema island`，不连接 OMS、实时风险预算或 signal。

### 9.2 V24 订单决策血缘

- `src/main/java/com/tj/crypto/autonomy/lineage/OrderDecisionEnvelope.java` 将市场事件、raw journal、archive、factor、signal、策略 release、Git/build/container/config、Agent trace、instrument spec、账户/风险/估值快照、资本/风险/执行政策、审批/capability 及 exact command 汇总为一个内容寻址 envelope；
- 确定性生产者必须为 `MODEL_NONE`；Agent 生产者必须有 proposal/model/prompt/context/tool trace；
- `OrderDecisionStore`、`OrderDecisionVerifier`、`OrderDecisionAdmissionPort` 默认拒绝且不返回 permit；
- `V24__create_order_decision_lineage.sql` 新增 append-only envelope、wire-dispatch 和 reconciliation evidence；OMS 指针为 legacy 兼容 nullable exact FK，旧订单继续明确为未绑定事实；
- 新表不可 UPDATE/DELETE，但数据库不能仅凭调用方给出的 hash 证明外部事实真实，可信 resolver/verifier 仍是必需边界。

验证入口：

```powershell
.\mvnw.cmd "-Dtest=OrderDecisionEnvelopeTest,LongTermAutonomyMigrationContractTest,LongTermOrderLineageMigrationContractTest" test
.\mvnw.cmd "-Dtest=LongTermAutonomyMigrationMySqlTest,LongTermOrderLineageMigrationMySqlTest" test
```

第二条要求 Docker/Testcontainers/MySQL 8 真实可用；skip 不是验收。当前 V23/V24 没有 mapper/service，现有 Signal/OMS 写路径也未被迁移到强制 envelope，因此每订单可追溯仍是 `NOT_WIRED`。

## 10. 交付八：L5 永不建设

- `AutonomyLevel` 只包含 `L3_ADVISORY` 和 `L4_BOUNDED_AUTONOMY`，没有 L5；
- Agent 包不提供提款、读取 secret、私有 gateway、权限/政策/硬风控修改、Kill Switch 恢复或自我晋级 API；
- execution、capital、readiness、lineage 的成功结果均硬性不是执行权限；
- V23/V24 不保存 secret、credential、withdrawal capability，也不 seed 可用授权；
- 任何未来变更若让 Agent 自由提款、修改自身权限/资本上限/硬风控、在线自修改并自行晋级，应被视为架构回归，而不是新功能。

L5 的状态是 **永不建设**，不能写成 `NOT_WIRED`、`待验收` 或“后续考虑”。

## 11. 串行集成验证与证据记录

所有模块稳定后，必须在干净 clone、JDK 17 和同一候选 commit 上串行执行：

```powershell
.\mvnw.cmd clean verify -B
cd frontend
npm ci
npm audit --omit=dev --audit-level=high
npm run lint
npm run build
npm run test:e2e
```

需要保存 commit SHA、JDK/Maven/Node/npm 版本、测试总数/failure/error/skipped、每个 skipped 的原因、MySQL/Testcontainers 是否真实运行、JAR/SBOM SHA-256、GitHub CI/Security run URL 和原始失败日志。

### 11.1 2026-08-24 最终快照

| 项目 | 结果 |
|---|---|
| 候选代码树 | 本分支“自治：建立长期有界控制面与订单血缘”提交；其后的文档与公开安全提交不改变构建输入 |
| 工具链 | JDK `17.0.15`；Maven `3.9.9`；Node `v25.9.0`；npm `11.12.1` |
| Java 全量回归 | workspace 临时目录下 `mvn clean test`：`1028 tests / 0 failures / 0 errors / 14 skipped`；随后 `mvn verify -DskipTests`：BUILD SUCCESS |
| MySQL/Testcontainers V23/V24 fresh + upgrade | **NOT_VERIFIED**：Docker daemon 不可用；14 个 skipped 均为真实 MySQL/Testcontainers 集成用例，其中 V23 为 2 项、V24 为 1 项 |
| 前端 audit/lint/build | `npm audit --omit=dev --audit-level=high`：0 vulnerability；`npm run lint`、`npm run build`：通过 |
| 前端 Playwright | mock-contract E2E `8/8` 通过；未运行真实 Spring/MySQL E2E，不能据此验证资金链 |
| JAR SHA-256 | `2b3aa70af3c08bf38a17d877d39a2ac5d2586b0ee4f442a7965c1624f42ea548` |
| CycloneDX SBOM SHA-256 | `78a1dbccaa570ee886162da069e312fff5c6e4bd6cfc31d5194b32575b97a5cd` |
| GitHub CI / Security | **NOT_VERIFIED**：本地证据完成时尚未推送；必须以 `codex/bounded-autonomy-roadmap` 的实际 workflow run 为准 |

14 个 skipped 的精确范围为：governance approval（1）、experiment registry（1）、live risk reservation（2）、V23 autonomy migration（2）、V24 order-lineage migration（1）、market-data catalog（2）、Flyway upgrade（1）、paper golden path（1）、strategy lifecycle（2）和 Agent sandbox migration（1）。这些用例必须在 Docker/MySQL 8 可用且不允许 skip-green 的受控 CI 中真实执行。

上述结果绑定本分支的候选代码树，只证明本地仓库回归；长期阶段仍须取得真实数据库、CI、owner venue、外部信任根与持续时间证据。

## 12. 外部与时间验收清单

长期 gate 的 12 类控制必须对 L3、L4 分别取得真实、不可变、签名且未过期的证据：

| 控制 | 必需原始证据 | 当前状态 |
|---|---|---|
| `LEGAL` | 明确主体、部署、账户、资产类别、venue、用户和服务模式的专项法律意见；实际部署与意见一致 | `NOT_VERIFIED` |
| `REPRODUCIBILITY` | 同一 commit/build/container/data/config/model 的干净重建、不可变 replay 和确定性复跑 | `NOT_VERIFIED` |
| `RESEARCH_ALPHA` | 预登记试验族、失败试验、多个未见市场状态、扣完整成本后的正 OOS 增量 | `NOT_VERIFIED` |
| `EXECUTION_CONSISTENCY` | backtest/paper/shadow/canary 的订单、成交、PnL 差异为零或全部预先容差内可解释 | `NOT_VERIFIED` |
| `RISK_CONTROLS` | Kill Switch、单写者、reduce-only、自动降级、人工接管和边界故障矩阵 | `NOT_VERIFIED` |
| `RECONCILIATION` | 订单、成交、余额、持仓、账本零未解决差异，含 ACK 丢失/重启/分区 | `NOT_VERIFIED` |
| `SECURITY` | 真实 KMS/Vault/HSM、无提款、IP allowlist、IdP/MFA、双人审批、轮换/撤销和零未批准 HIGH/CRITICAL | `NOT_VERIFIED` |
| `OPERATIONS` | 至少 7 天连续 soak、chaos、PITR、RPO/RTO、外部告警到达、值班和零未解释事故 | `NOT_VERIFIED` |
| `AGENT_ASSURANCE` | 真实模型/runner trace、注入/越权/投毒/secret 红队、立即撤销、L3 逐建议审批、L4 预批准 envelope 和无 Agent 基线增量 | `NOT_VERIFIED` |
| `CAPITAL_CONTROLS` | owner 隔离子账户、无提款、精确最大名义/日损/TTL、真实累计预算与 `UNKNOWN` 保留 | `NOT_VERIFIED` |
| `OWNER_VENUE_CONTRACT` | owner 账户的产品/权限/精度/限频/place-query-cancel/私有流/全量账户真相合同 | `NOT_VERIFIED` |
| `CROSS_MARKET_STATE` | 至少两个真实市场状态、逐单完整 trace、真实小资金成交与零未解释事件 | `NOT_VERIFIED` |

此外，路线图要求的 12～24+ 个月演进、30～90 天 forward paper/shadow、每个真实资本阶梯、小资金 live canary 和多个市场状态不能由固定时钟、加速测试或补写文档替代。每项证据必须记录开始/结束时间、环境、账户 scope、负责人、失败、原始 artifact URI、SHA-256、签名和独立复核。

## 13. 长期阶段退出判断

当前长期阶段退出条件**未满足**：

1. P0、短期和中期阶段仍有 `NOT_WIRED`、真实环境和持续时间缺口，长期阶段不能越级通过；
2. 资产类别无关能力目前只覆盖资本、标的规格和不可派单规划原语，现有 backtest/paper/live/OMS/venue 尚未迁移；
3. L3/L4 没有真实模型、外部信任根、持久 store、OMS/gateway consumer 或目标环境证据；
4. canary/watchdog 只返回确定性决策/intent，没有资金面原子 applier；
5. V23/V24 仍是 schema/contract island，每订单 lineage 没有成为现有 OMS 强制入口；
6. 专项法律、owner venue、KMS/MFA、真实 MySQL/CI、灾备/值班、30～90 天及多个市场状态证据尚未完成；
7. L5 按永久禁止边界没有、也不会有阶段退出路径。

因此发布、部署和运行必须继续满足：

- `TRADING_OPERATING_MODE=RESEARCH_ONLY`；
- `LIVE_TRADING_WRITE_ENABLED=false`；
- `BINANCE_WRITE_ENABLED=false`；
- `OKX_WRITE_ENABLED=false`；
- Agent 不取得 secret、Signer、OMS、gateway、Kill Switch 恢复、政策修改或权限提升能力；
- 任一 evidence、current head、PIT fact、身份、store、verifier、时钟或外部服务缺失/过期/不一致时保持 **NO-GO / HALT**。

本轮的完成口径只能是：**长期安全契约与确定性原语的仓库实现完成待验收；长期目标整体、L3/L4 端到端能力和真实资金自治均未完成。**
