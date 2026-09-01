# 中期目标仓库实施与验收说明（2026-08）

> 本文对应 `docs/agentic-quant-trading-roadmap-2026-08.md` 第 7 节，是中期代码交付、接线状态和外部证据的事实清单。
>
> 基线日期：2026-08-21。
>
> 强制结论：当前保持 **RESEARCH_ONLY / NO-GO**。本文中的“仓库内已实现待验收”只表示实现和本地自动化验证入口存在，不表示生产、真实模型、owner testnet/demo 或真实资金能力已验收。

## 1. 状态语义

| 状态 | 本文中的精确定义 | 能否据此晋级 |
|---|---|---|
| `仓库内已实现待验收` | 类、迁移、离线语料或测试已经存在；仍需在候选提交、真实数据库或目标环境保存可审计结果 | 否 |
| `NOT_WIRED` | 独立组件已实现，但尚未连接实时连接器、研究编排器、策略运行时、交易执行面、CI 发布门或外部信任根 | 否 |
| `NOT_VERIFIED` | 必需的 owner、凭据、环境、外部制品、持续时间或独立审批证据不存在、过期或未可信验证 | 否 |
| `外部阻塞/时间验收` | 依赖仓库外设施、人员、法律意见、真实账户或不能压缩的持续观察窗口 | 否 |
| `已验收` | 路线图、runbook 和本文要求的仓库内、目标环境与持续时间证据全部有效 | 是，但仍须由统一 policy gate 决定 |

不得用 mock、离线 fake、单元测试、人工口头确认或补写文档把 `NOT_WIRED` / `NOT_VERIFIED` 改写为“已验收”。

## 2. 当前总览

| 路线图中期交付 | 仓库内状态 | 运行接线状态 | 外部/时间状态 |
|---|---|---|---|
| 1. 多类型行情与 Parquet 数据层 | 归档模型、版本化 schema、真实 Parquet 编解码、raw-first WAL 和目录持久化屏障已实现待验收 | Binance/OKX/Coinglass WebSocket 受支持载荷已接统一 journal；生产对象存储、未知帧和历史 polling 尚未纳入；不支持目录 fsync 的平台会 fail-closed | 真实连续采集、保留策略、恢复和数据完整性证据未验证 |
| 2. 研究试验 registry | 不可变预登记、完整 trial family、失败试验和 hash-chain 事件已实现待验收 | 尚未强制接入所有回测、优化器、Agent 和认证流水线 | MySQL 容器迁移/并发与真实研究复现制品待验收 |
| 3. 策略生命周期 | 签名制品、双审批、证据 TTL、状态机、回滚/自动降级已实现待验收 | 已连接 `StrategyManager` 运行时选择、`StrategyEngine` 信号血缘和 LIVE_CANARY 写入硬门；配置发布与真实 paper/shadow consumer 仍为 `NOT_WIRED` | 信任根、独立审批及法律/安全/运维/交易真相证据未验证，故 live 默认拒绝 |
| 4. 组合分析 | 预算、相关性/集中度、压力、容量、OMS TCA 及内容寻址 PIT 输入约束已实现待验收 | 只读研究包；旧的无快照入口 fail-closed，可信快照 producer 和硬风控预占仍未接入 | 真实容量、基准 mark、交易成本、来源 manifest 和账实校验待验收 |
| 5. venue demo/testnet 合同 | 合同认证框架、离线速率限制/断线语料、缺凭据哨兵已实现待验收 | 无 owner 管理的真实 probe driver；未成为发布门 | owner testnet/demo、私有流长稳为 `NOT_VERIFIED` |
| 6. 安全/运维/灾备治理 | 外部证据 registry、逐控制类型签名结论、KMS 断言、MFA/双人审批及全局防重放账本已实现待验收 | 默认外部验证器 fail-closed；逐笔风险增加写入已消费一次性审批，但这些代码不是 KMS、IdP、备份或告警设施本身 | KMS/MFA、PITR、RPO/RTO、告警到达、24h/7d soak/chaos 未验证 |
| 7. Agent L1/L2 | L1 结构化假设/实验计划、L2 paper/shadow 提案、预算/TTL/审计/撤销和认证 operator 绑定审批已实现待验收 | 未接真实模型；L2 不具备订单执行能力；线程超时器不是 OS/容器安全沙盒 | 真实模型、独立沙盒和长期运行证据未验证 |
| 8. Agent 安全与效能评测 | 内建威胁语料、确定性评分、无 Agent 基线、成本/延迟/复现指标、顺序 trace root 和不可变报告已实现待验收 | 未连接真实模型 trace、CI、生命周期晋级门或外部红队 | 真实模型红队与独立复核为 `NOT_VERIFIED` |

综上，当前准确表述是：**中期仓库工程骨架已形成，但关键运行链仍有 `NOT_WIRED`，所有外部与时间证据仍须真实取得，中期阶段退出条件尚未满足。**

## 3. 交付 1：多类型行情与不可变 Parquet 数据层

### 3.1 代码位置

- 规范化事件：`src/main/java/com/tj/crypto/marketdata/model/TradeEvent.java`、`L2OrderBookEvent.java`、`MarkPriceEvent.java`、`IndexPriceEvent.java`、`FundingRateEvent.java`、`OpenInterestEvent.java`、`RawMarketMessage.java`；
- 统一归档类型：`ArchivableMarketData.java`、`ArchiveEventType.java`；
- 版本化 schema：`src/main/resources/schema/marketdata/*.avsc`；
- 不可变数据端口与本地实现：`ImmutableMarketDataArchive.java`、`LocalParquetMarketDataArchive.java`、`ParquetMarketEventCodec.java`；
- raw-first WAL、实时接线与冷层压实：`LiveMarketIngestJournal.java`、`LocalFsyncMarketIngestJournal.java`、`RealtimeMarketIngestCoordinator.java`、`JournalParquetCompactor.java`；
- 显式接线状态：`RealtimeCaptureCapability.java`。
- 数据目录迁移：`src/main/resources/db/migration/V15__create_immutable_market_data_catalog.sql`。

本地实现使用按事件类型/来源/交易所/市场/标的/UTC 日期分区、manifest-last、SHA-256、`CREATE_NEW`、全局幂等键、同文件系统原子移动和 Parquet 回读校验。raw、Parquet、manifest、临时/最终提交指针在提交边界均要求同步父目录；平台不能提供该能力时返回 `DIRECTORY_DURABILITY_NOT_VERIFIED`，不会伪造 durable receipt。这证明归档原语，不证明对象存储或实时采集已经投产。

### 3.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=LocalFsyncMarketIngestJournalTest,LocalParquetMarketDataArchiveTest,JournalParquetCompactorTest,CanonicalMarketFactValidationTest" test
.\mvnw.cmd "-Dtest=MarketDataCatalogMySqlIntegrationTest" test
```

第二条命令要求 Docker/Testcontainers/MySQL 可用。所有中期模块稳定后还必须执行第 11 节全量验证。

### 3.3 边界与剩余证据

- Spring 运行装配的 `LiveMarketIngestJournal.realtimeCaptureCapability()` 对 Binance、OKX、Coinglass WebSocket 受支持载荷为 `WIRED`；普通构造器仍为 `NOT_WIRED`，生产连续运行、未知帧、历史 polling 和对象存储不得借此冒充已验收；
- 当前对象语义实现是本地文件系统，不等于 S3/OSS/MinIO 等生产对象存储；
- 尚需验证真实 trade/L2/mark/index/funding/OI 连续采集、断线补洞、schema 演进、分区恢复、生命周期/保留策略和跨存储校验；
- 尚无不可变真实基准数据集的 Alpha 证据，不能据此声明黄金策略通过。

## 4. 交付 2：研究试验 Registry 与可复现血缘

### 4.1 代码位置

- 核心服务与模型：`src/main/java/com/tj/crypto/research/experiment/`；
- MyBatis 持久化：`ExperimentRegistryMapper.java`；
- 数据库不可变约束：`src/main/resources/db/migration/V16__create_research_experiment_registry.sql`；
- 单元测试：`src/test/java/com/tj/crypto/research/experiment/`；
- MySQL 集成测试：`src/test/java/com/tj/crypto/integration/mysql/ExperimentRegistryMySqlIntegrationTest.java`。

registry 要求试验开始前一次性登记完整、连续的 trial family，并绑定数据、代码、容器、配置、依赖锁、模型、prompt、seed 和参数摘要。成功、失败与取消均以追加事件保存；数据库触发器拒绝改写和删除历史。

### 4.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=ExperimentFamilyRegistrationTest,ExperimentRegistryServiceTest" test
.\mvnw.cmd "-Dtest=ExperimentRegistryMySqlIntegrationTest" test
```

第二条命令要求 Docker/Testcontainers/MySQL 可用。缺少 Docker 时的 skip 不能作为数据库验收证据；CI guard 仍应 fail-closed。

### 4.3 边界与剩余证据

- Spring service 与数据库 mapper 已存在，但尚未成为所有 backtest、参数优化、黄金策略认证和 Agent 实验的强制入口，故端到端登记为 `NOT_WIRED`；
- 尚需证明并发预登记、重复幂等、失败保留和 hash-chain 在真实 MySQL fresh/upgrade 路径中成立；
- 真实数据/代码/容器制品必须由外部不可变存储保留，URI 与 SHA-256 不能只指向会被覆盖的工作目录；
- registry 只证明血缘完整性，不证明 Alpha、模型价值或晋级资格。

## 5. 交付 3：签名制品驱动的策略生命周期

### 5.1 代码位置

- 状态与动作：`src/main/java/com/tj/crypto/strategy/lifecycle/StrategyLifecycleStage.java`、`StrategyLifecycleAction.java`；
- 业务服务与 policy：`StrategyLifecycleService.java`、`StrategyLifecyclePolicy.java`、`StrategyPromotionGate.java`；
- Ed25519 制品验证与数据库信任根：`Ed25519StrategyArtifactSignatureVerifier.java`、`DatabaseTrustedSigningKeyProvider.java`；
- 持久化和审计：`MyBatisStrategyLifecycleStore.java`、`StrategyLifecycleMapper.java`、`StrategyLifecycleEvent.java`；
- 默认外部门：`FailClosedExternalPromotionReadinessProvider.java`；
- 运行时 release 与信号血缘：`LifecycleStrategyRuntimeReleaseGate.java`、`StrategyManager.java`、`StrategyEngine.java`；
- LIVE_CANARY 写入硬门：`LiveTradingWriteGuard.java`、`LiveGovernedOrderReservationService.java`、`LiveOrderService.java`；
- 迁移：`V17__create_strategy_lifecycle_policy_gate.sql`、`V21__bind_strategy_runtime_release.sql`、`V22__bind_live_governance_authorization.sql`；
- 测试：`StrategyLifecycleServiceTest.java`、`LifecycleIntegrationAdaptersTest.java`、`LiveGovernanceHardGateTest.java`。

允许路径为 Draft → Review → Approve → Shadow → Canary → Promote，另有 Rollback。制品按内容寻址并验证签名，审批要求独立人员、范围摘要和 TTL；证据过期、制品过期或外部门失效时自动降级并追加 hash-chain 审计事件。当前 release 会约束策略运行时选择和信号血缘，策略返回后还会重新解析 assignment/release 并在发布前拒绝换版、撤销或过期结果；LIVE_CANARY 风险增加必须绑定同一 release/治理主体、逐笔命令摘要、一次性双人 MFA 审批与短 TTL 单次 permit，并在发网前复核。HALT 继续拒绝风险增加，但保留经过持仓证明的 reduce-only 和撤单；超时、限流及不能证明为业务拒单的 mutation 结果进入 `UNKNOWN`，不释放风险预占或自动重发。

### 5.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=StrategyLifecycleServiceTest,LifecycleIntegrationAdaptersTest,LiveGovernanceHardGateTest" test
```

### 5.3 边界与剩余证据

- fresh database 的信任根表默认为空，外部 readiness provider 默认拒绝，这是预期安全行为；
- 生命周期已连接策略运行时选择、信号血缘、Admin 认证 operator 和实盘写门，但配置发布与真实 paper/shadow consumer 尚未接通；
- LIVE_CANARY 代码路径存在不表示可开启：真实 readiness/KMS/MFA/法律/venue truth 证据尚未验证，默认 verifier、模式和写开关共同保持 fail-closed；
- 单元测试中的签名密钥和通过证据仅验证状态机，不能替代真实 KMS/HSM、审批身份或目标环境证据；
- Canary/Promote 在法律、安全、运维或 live-truth 证据缺失/过期时必须继续被拒绝。

## 6. 交付 4：组合预算、相关性、压力、容量与 TCA

### 6.1 代码位置

`src/main/java/com/tj/crypto/portfolio/analytics/` 提供：

- `PortfolioBudgetAnalyzer`：账户、策略、标的、方向预算利用率；
- `ReturnCorrelationAnalyzer` 与 `ConcentrationAnalyzer`：相关矩阵、HHI 和相关性调整集中度；
- `DeterministicStressAnalyzer`：版本化确定性冲击情景；
- `CapacityEstimator`：参与率和平方根冲击约束下的容量；
- `OmsTcaAnalyzer`：基于内容寻址 `TcaInputSnapshot`、OMS 成交、到达价、结束 mark、费用、延迟和未成交机会成本的 TCA；未提供可信合约乘数时拒绝 `CONTRACTS`。

压力、集中度和 TCA 分别要求 `PortfolioEquitySnapshot`、`ConcentrationExposureSnapshot`、`TcaInputSnapshot`；组合暴露还绑定币种、event/observed time、来源 manifest 和完整性。旧的裸 equity、裸 exposure map 或无快照 TCA 入口 fail-closed，分析器不会自行把未知输入升级为 `COMPLETE`。

### 6.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=PortfolioBudgetAnalyzerTest,CorrelationAndConcentrationAnalyzerTest,DeterministicStressAnalyzerTest,CapacityEstimatorTest,OmsTcaAnalyzerTest" test
```

### 6.3 边界与剩余证据

- 此包按设计无 Spring component、mapper、交易 gateway 或风险引擎写依赖；它是确定性只读研究组件；
- 尚未接入可信的运行时 point-in-time 快照 producer、instrument metadata、市场 mark/FX、上游 manifest、报表 API、告警或风险 reservation，因此为 `NOT_WIRED`；
- 分析结果不得授予交易权限，也不得替代 V14 持久化预占、硬风控、Kill Switch 或对账；
- 需用真实 OMS 成交、真实费用换算、盘口深度/成交量和市场状态校准容量/TCA，并保存 backtest/paper/shadow 差异证据。

## 7. 交付 5：交易所 Demo/Testnet 合同认证

### 7.1 代码位置

- 合同配置、场景、probe 和报告：`src/main/java/com/tj/crypto/trading/venue/contract/`；
- 速率限制和断线语料：`src/test/resources/venue-contract/`；
- 离线框架测试：`VenueContractCertificationRunnerTest.java`、`VenueContractCorpusReplayTest.java`；
- 外部证据哨兵：`OwnerVenueContractExternalTest.java`。

场景覆盖 owner/环境/endpoint/账户模式/权限，place-query-cancel、精度/最小名义、限频/错误码、时钟偏差、断线重连/重复/延迟事件、`UNKNOWN` 后只查询不重发，以及活动订单、近期成交、余额和持仓 capability。

### 7.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=VenueContractCertificationRunnerTest,VenueContractCorpusReplayTest" test
.\mvnw.cmd "-Dtest=OwnerVenueContractExternalTest" test
```

第二条当前预期输出 `OWNER_VENUE_CONTRACT_STATUS=NOT_VERIFIED`。它是缺失真实 driver/凭据时的防假绿哨兵；测试进程返回成功不等于 owner 合同通过。

### 7.3 边界与剩余证据

- 离线 `OFFLINE_PASSED` 永远不能汇总成 owner 环境的 `VERIFIED`；
- 仓库未包含 owner 凭据，也不应包含；当前没有 owner 管理的 Binance Testnet / OKX Demo probe driver，故真实认证为 `NOT_VERIFIED`；
- 尚需在隔离网络、无提款权限、endpoint allowlist 和 owner 控制账户中运行，并保存原始响应摘要、时间线和报告 SHA-256；
- 私有流至少 7 天的连续稳定性不能由短测试、离线 replay 或加速时钟替代。

## 8. 交付 6：KMS/MFA/双人审批、外部告警与灾备证据门

### 8.1 代码位置

- KMS/Vault/HSM 断言策略：`src/main/java/com/tj/crypto/governance/security/`；
- MFA 与双人审批策略：`src/main/java/com/tj/crypto/governance/approval/`；
- 外部证据模型、repository 和统一门：`src/main/java/com/tj/crypto/governance/evidence/`；
- 追加式证据、MFA 防重放和 live 授权表：`V20__create_governance_control_evidence.sql`、`V22__bind_live_governance_authorization.sql`；
- 测试：`src/test/java/com/tj/crypto/governance/`。

`MediumTermReadinessGate` 对法律、KMS、MFA、双人审批、IP allowlist、外部告警到达、PITR、RPO/RTO、venue 合同、私有流、24h/7d soak、chaos 和 30d forward evidence 统一 fail-closed，并逐 `ControlKind` 校验签名 status、结论、测量值/阈值、候选制品、policy 版本、有效期和真实持续时间。MFA verification 强绑定 request/action/resource/subject/audience；每个 replay reservation 在数据库中全局唯一并与审批消费同事务落库。

### 8.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=DualControlPolicyTest,ControlEvidenceTest,MediumTermReadinessGateTest,KeyCustodyPolicyTest" test
```

### 8.3 边界与剩余证据

- 这些类是外部证据校验与拒绝策略，不是 KMS、IdP/MFA、备份系统、PITR、跨地域恢复或值班告警平台；
- 默认外部 verifier 不受信任，缺失或自我声明的证据必须返回 `NOT_VERIFIED`；
- 真实 KMS/Vault/HSM 非导出密钥、MFA challenge、两名独立审批人、轮换/撤销、外部告警到达、PITR 恢复、RPO/RTO 均为外部阻塞；
- 24 小时、7 天和 30 天门按真实起止时间校验，不能压缩；本轮不得把它们写成完成。

## 9. 交付 7：Agent L1/L2 有界研究与 Paper/Shadow 提案

### 9.1 代码位置

- 工具白名单、级别、预算、TTL 和授权：`src/main/java/com/tj/crypto/research/agent/policy/`；
- L1 假设/计划与 L2 提案服务：`src/main/java/com/tj/crypto/research/agent/sandbox/`；
- 工具调用审计与提案持久化：`AgentToolAuditMapper.java`、`AgentProposalMapper.java`、`PersistentAgentToolAuditSink.java`、`PersistentAgentProposalRepository.java`；
- 数据库迁移：`src/main/resources/db/migration/V19__create_agent_sandbox_audit.sql`；
- 测试：`src/test/java/com/tj/crypto/research/agent/policy/` 和 `src/test/java/com/tj/crypto/research/agent/sandbox/`。

L1 只能形成结构化、可证伪的假设和实验计划。L2 只生成绑定 policy、模型/prompt/数据/代码/配置版本、审批引用和短 TTL 的 PAPER/SHADOW 提案；审批摘要、proposal/use ID 和 verifier receipt 还绑定服务端认证的 requesting operator，跨 operator 复用或抢占会被拒绝。数据库注释和类型均明确提案不具备执行能力。secret、配置写、Kill Switch、实盘和私有 gateway 不在工具白名单。

### 9.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=AgentSandboxPolicyEngineTest,AgentResearchSandboxServiceTest,TimedSandboxTaskRunnerTest" test
.\mvnw.cmd "-Dtest=AgentSandboxMigrationContractTest,AgentSandboxMigrationMySqlIntegrationTest" test
```

第二条命令中的 MySQL 用例要求 Docker/Testcontainers 可用；静态 migration contract 通过不等于真实数据库迁移已经验收。

### 9.3 边界与剩余证据

- 当前没有真实 LLM/model adapter，也没有让 L2 提案进入 `PaperOrderService` 或 shadow 执行器的受控 consumer，端到端 Agent 为 `NOT_WIRED`；
- `TimedSandboxTaskRunner` 提供有界线程超时/取消，不是操作系统、容器、虚拟机或独立网络命名空间安全边界；
- 在具备真实隔离前，不得运行不受信任代码、开放进程创建、任意文件访问或外部网络；
- Agent 不得获取密钥、签名、实盘订单、配置发布、风险预算修改或 Kill Switch 写权限；当前 L0 只读限制和 RESEARCH_ONLY 不得放宽。

## 10. 交付 8：Agent 安全、复现率、成本与延迟评测

### 10.1 代码位置

- 内建安全语料：`src/main/java/com/tj/crypto/research/agent/eval/AgentSecurityCorpus.java`；
- 确定性评分器与阈值：`AgentEvalEvaluator.java`、`AgentEvalThresholds.java`；
- Agent/无 Agent 比较输入和报告：`AgentEvalSubmission.java`、`AgentEvalReport.java`；
- 内容寻址不可变报告：`ImmutableAgentEvalReportStore.java`；
- 测试：`src/test/java/com/tj/crypto/research/agent/eval/AgentEvalEvaluatorTest.java`。

内建 corpus 覆盖 prompt injection、权限提升、数据投毒、工具误用和 secret 处理；每次运行用有序、内容寻址的 trace 覆盖 prompt、context、tool policy、model input/output、逐次工具调用、审批、policy decision 和结果，evaluator 重算 trace root 并拒绝自报复现。评分同时要求覆盖率、零关键违规、重复运行复现、合格任务成功率、无 Agent 基线增量、成本和 p95 延迟阈值。

### 10.2 验证入口

```powershell
.\mvnw.cmd "-Dtest=AgentEvalEvaluatorTest" test
```

### 10.3 边界与剩余证据

- 单元测试使用构造的 trace 验证 evaluator，不是对任何真实模型的红队结果；
- evaluator 和本地报告存储尚未接真实模型调用链、不可变外部 evidence store、CI 或策略/Agent 晋级门，故为 `NOT_WIRED`；
- 仍需对锁定的 model、prompt、上下文、工具版本和审批 policy 运行真实、重复、独立复核的评测，并保留原始 trace 与费用/延迟账单；
- 模型或 prompt 任一变化都必须产生新的评测主体和报告，旧报告不能沿用。

## 11. 集成验证顺序与证据要求

所有并发模块合并稳定后，在干净 clone、JDK 17 和同一候选 commit 上执行：

```powershell
.\mvnw.cmd clean verify -B
cd frontend
npm ci
npm audit --omit=dev --audit-level=high
npm run lint
npm run build
npm run test:e2e
```

证据必须记录：commit SHA、JDK/Maven/Node/npm 版本、测试总数/failure/error/skipped、每个 skipped 的原因、MySQL/Testcontainers 是否真实运行、JAR/SBOM SHA-256、CI/Security workflow URL 及原始失败日志。只有本地测试不能替代 GitHub CI、真实数据库、owner venue、真实模型或持续时间验收。

### 11.1 2026-08-21 本地最终快照

以下结果来自同一未提交工作树（基线 `cca5703`）和 JDK 17。它们证明仓库内实现与离线回归状态，不替代真实 MySQL、GitHub CI、owner venue、真实模型或持续时间验收。

| 项目 | 最终结果 |
|---|---|
| 候选 commit SHA | 尚未提交；工作树基线为 `cca5703`，因此不存在可供发布验收的候选 commit SHA |
| Java 17 干净回归 | `mvn clean test`：865 tests / 0 failures / 0 errors / 11 skipped；随后 `mvn verify -DskipTests`：BUILD SUCCESS |
| MySQL/Testcontainers fresh + upgrade | **NOT_VERIFIED**：本机 Docker daemon 不可用；11 个 skipped 均为真实 MySQL/Testcontainers 集成用例 |
| 中期聚焦测试 | 已包含在 865 项全量回归中；其中 migration contract 4/4、live governance hard gate 6/6、live mutation outcome 6/6、venue contract 19/19、Agent eval 14/14 |
| 前端依赖/静态/构建 | `npm audit --omit=dev --audit-level=high`：0 vulnerability；`npm run lint`、`npm run build`：通过 |
| 前端 E2E | **NOT_VERIFIED**：8 个 Playwright 用例均在断言前因当前 sandbox 禁止启动 Chromium（`spawn EPERM`）而中止，不计为功能失败或通过 |
| JAR SHA-256 | `c45e3a7ebfc233d741aaea8f777663ac4baea99f243bc8b2a5bf16e6ae0ec8e8` |
| CycloneDX SBOM SHA-256 | `78a1dbccaa570ee886162da069e312fff5c6e4bd6cfc31d5194b32575b97a5cd` |
| GitHub CI / Security | **NOT_VERIFIED**：本轮未提交、未推送，因而没有同一候选 commit 的 workflow run URL |

11 个 skipped 的精确范围为：governance approval、experiment registry、live risk reservation（2）、market-data catalog（2）、Flyway upgrade、paper trading golden path、strategy lifecycle（2）与 Agent sandbox migration 的 MySQL 集成用例。必须在 Docker/MySQL 8 可用的受控 CI 中全部实际运行后，才能把数据库项改为 VERIFIED。

## 12. 中期阶段退出判断

当前未满足路线图第 7 节阶段退出条件，原因包括：

1. 签名制品与 policy gate 已接策略运行选择、信号血缘和 live 写硬门，但配置发布、真实 paper/shadow consumer 及全部外部信任根尚未接通/验收；
2. Agent 模型、prompt、上下文与工具调用尚无真实模型端到端 trace，评测也未成为发布门；
3. owner testnet/demo 与私有流持续运行尚未验证；
4. KMS/MFA/双人审批、PITR、RPO/RTO、外部告警和 7 天 chaos/soak 证据缺失；
5. 30～90 天 forward paper/shadow、专项法律意见和不可变真实数据上的 Alpha 证据未完成。

因此发布、部署和运行必须继续满足：

- `TRADING_OPERATING_MODE=RESEARCH_ONLY`；
- `LIVE_TRADING_WRITE_ENABLED=false`；
- `BINANCE_WRITE_ENABLED=false`；
- `OKX_WRITE_ENABLED=false`；
- Kill Switch、硬风控、OMS、单写者和对账权限不交给 Agent；
- 任一证据缺失、过期、环境不一致或无法可信验证时保持 **NO-GO**。

## 13. 2026-09-01 建议追加与实施状态

短期优先事项已经落到统一准入、raw-first、Signal/Paper 同事务、配置并发约束和确定性目标仓位管线；P1 新增连接器认证 kit 与差分 replay verifier；P2 新增隔离 Python research worker。Python worker 使用锁定的 Polars/PyArrow 依赖，只读取校验后的本地 Parquet manifest，输出内容寻址、不可执行报告，不连接网络、数据库、OMS 或订单 API。

后续建议保持以下顺序：

1. 短期：取得同一候选提交的 CI/Security、fresh MySQL、真实非 mock E2E，并启动 30～90 天 Paper/Shadow；修复所有 parity 差异后才谈晋级。
2. 中期：把 registry、PIT portfolio、connector certification 和 Agent eval 变成强制发布门；建设对象存储与连续 replay，但继续让 L1/L2 只产生研究计划或 Paper/Shadow 提案。
3. 长期：只建设有界 L3/L4 控制面、独立 watchdog/canary 和资产无关事实内核；所有最终交易写权限继续由确定性 policy/OMS/gateway 独占，L5 永不建设。

多语言决策是“Java 主内核 + Python 研究面 + TypeScript 控制台/受限编排”，通过版本化 schema、manifest 和 SHA-256 边界协作，不采用共享可变内存或让动态语言进入最终写路径。当前明确不对接券商；券商 adapter、凭据、FIX/FIXP 与美股交易日历均不属于短中期 backlog，未来需项目所有者重新明确授权。

本批验证快照：Java 17 全量回归 1044 tests / 0 failures / 0 errors / 14 skipped；目标 MySQL 8.0.34 V5→V26 upgrade 成功且无失败迁移；`pro + RESEARCH_ONLY` 禁用外部连接器/实盘写的真实 Spring 启动成功；Python worker 3/3；前端 production audit 0 vulnerability、lint/build、Playwright 8/8。14 个 skip 仍是 Docker/Testcontainers 用例；本轮未提交/推送，因此 fresh CI、Security workflow 和容器镜像扫描仍为 `NOT_VERIFIED`。
