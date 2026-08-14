# P0 验收与 NO-GO Runbook

> 本文是 `agentic-quant-trading-roadmap-2026-08.md` 的执行清单。勾选代码项不等于授权真实资金；没有原始证据链接的项目一律视为未通过。

## 1. 当前强制状态

- 默认和生产模板必须保持 `TRADING_OPERATING_MODE=RESEARCH_ONLY`。
- `LIVE_TRADING_WRITE_ENABLED`、`BINANCE_WRITE_ENABLED`、`OKX_WRITE_ENABLED` 必须为 `false`。
- 中国大陆主体、用户、部署或服务链路涉及虚拟货币交易时，专项法律意见完成前不得启用实盘。
- Agent 只允许 L0 固定白名单研究查询；不得取得交易所凭据、签名、订单、配置发布或 Kill Switch 写能力。

任何检查失败时：保持/切换 `HALT`，禁止增加风险；只允许经独立权限授权的查询、对账、撤单和已验证减仓流程。

## 2. 每个候选提交的本地证据

在干净 clone、JDK 17 上执行：

```bash
./mvnw clean verify -B
cd frontend
npm ci
npm audit --omit=dev --audit-level=high
npm run lint
npm run build
npm run test:e2e
```

必须保存：commit SHA、JDK/Maven/Node/npm 版本、JAR/SBOM SHA-256、测试汇总和失败日志。`target/build-provenance.json`、`target/SHA256SUMS` 由 CI 生成。

## 3. CI 与真实数据库

- GitHub `CI` 和 `Security` workflow 必须在同一 commit 上实际运行成功；本地静态检查不能替代。
- MySQL/Testcontainers 必须同时执行：全新库迁移黄金路径、V11 到当前版本升级路径。CI 无 Docker 时 guard 必须失败，不能 skip-green。
- 非 mock 浏览器测试必须使用构建后的前端 `dist`，登录真实 Spring 服务并读取真实 overview。
- CodeQL、Gitleaks、Trivy、依赖审查必须无未批准 HIGH/CRITICAL；例外必须有责任人、原因和到期日。
- 容器必须以非 root 运行，使用 digest 固定的基础镜像，持久 spool/export volume 可写且可恢复。

## 4. 交易安全故障矩阵

至少保存以下演练的时间线、数据库快照、OMS 事件、交易所响应和告警：

| 故障 | 必须观察到的结果 |
|---|---|
| Kill Switch 数据库不可读 | 所有节点本地 `HALT`，不得自动恢复 `NORMAL` |
| 远端节点切换 `HALT` | 其他节点在配置的刷新上限内收敛；新增加风险订单为 0 |
| writer lease 丢失/接管 | 接管触发 `HALT`；完成对账和显式批准前不能开仓 |
| 下单 ACK 丢失/5xx/超时 | OMS 为 `UNKNOWN`；只查询、不重发；开仓门关闭 |
| 启动存在 UNKNOWN 或恢复积压 | recovery 为 `BLOCKED`，Kill Switch 为 `HALT` |
| 活动挂单占用预算 | 后续订单计入剩余名义；并发请求不能越过账户/标的/组合上限 |
| Sandbox 配生产域名 | 启动或每次写入 fail-closed，网络侧不得出现生产交易请求 |
| 行情乱序、冲突重复、gap、spool/DB 故障 | 事件不进入策略并触发 `HALT`；已闭合 K 线可重放且不丢失 |
| Outbox 无 handler 或 handler 失败 | 不得标记 published；按策略重试或进入 dead letter |

交易所不接受本系统 fencing token，因此数据库租约不能数学上隔离“旧进程暂停后恢复”的最后网络窗口。进入任何资金环境前，还必须使用单独 signer/网络出口/短期凭据或等价的外部隔离，并验证交易所 `clientOrderId` 幂等语义。

## 5. Sandbox/demo 合同认证

- 使用 owner 控制的 Binance Testnet / OKX Demo 凭据；禁止提款权限。
- 验证产品、账户模式、hedge/one-way、margin mode、精度、最小名义、费率、限频和错误码。
- 验证 place/query/cancel、部分成交、重复事件、断线重连、listen key、未知状态和时钟偏差。
- 同时拉取交易所活动订单、近期成交、余额和持仓，证明能发现 venue orphan 与内部缺失事实；adapter 报 `UNSUPPORTED` 时不得晋级。
- Sandbox endpoint 必须通过代码 allowlist 和网络出口 allowlist 双重约束。

## 6. 策略证据与观察窗口

- 输入数据必须有完整 instrument identity、起止时间、schema/version、行数和 SHA-256 manifest。
- 预登记完整试验族；失败试验不得删除。报告必须包含 purged/embargo、DSR、CSCV/PBO、多重检验、手续费、资金费、滑点、延迟、容量与简单基线。
- Backtest/Paper/Shadow 的同一事件输入需逐笔 parity；差异必须有预设容差和解释。
- 至少完成 24 小时、7 天故障/soak，以及 30～90 天 forward paper/shadow。观察窗口不能通过加速测试或补写文档替代。

## 7. 外部批准

最终晋级记录必须同时包含：专项法律意见、运营主体和部署范围、KMS/Vault/HSM、IP 白名单、MFA、双人审批、密钥轮换/撤销、备份/PITR、RPO/RTO、值班与告警到达证据。

只要任一证据缺失、过期或环境不一致，结论保持 **NO-GO / RESEARCH_ONLY**。

## 8. 2026-08-14 执行记录

- 本地 JDK 17 `./mvnw clean verify -B`：716 tests，0 failure，0 error，4 skipped；跳过项均为 Docker 不可用的 MySQL/Testcontainers 测试。之后新增“无私有 venue 时只阻断 live gate、不误停 paper”回归，相关 12 项聚焦测试通过；下一次全量基线应为 717 项。
- 连续两次同输入 package：JAR 与 SBOM 的 SHA-256 分别保持一致，证明当前本地构建可重复。
- 前端生产依赖审计 0 漏洞，lint/build 通过，8 项 mock-contract Playwright E2E 通过。
- MySQL fresh/upgrade、非 mock Spring/MySQL E2E、安全 workflow、镜像构建/扫描：实现已进入 CI，尚无远端运行证据，仍为“已实现待验收”。
- 交易所真相门当前因 Binance/OKX 活动订单/近期成交和内部账户级投影为 `UNSUPPORTED` 而保持 `BLOCKED`；专项法律、owner demo 凭据、KMS/MFA/双人审批、DR/soak 和 30～90 天 forward 证据仍未完成。
