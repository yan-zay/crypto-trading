/**
 * Agent L3/L4 的不可执行安全契约。
 *
 * <p><strong>NOT_WIRED / RESEARCH_ONLY：</strong>本包没有且不得依赖订单、OMS、签名器、私有
 * venue gateway、密钥、提款、配置发布或硬风控写接口。L3 只返回经独立人类审批的建议回执；
 * L4 只返回由持久原子 port 管理的内部 capability/审计回执，二者均不是执行凭证。
 *
 * <p>生产接线前必须另外完成：
 *
 * <ul>
 *   <li>从当前认证上下文/mTLS workload（而非自报字符串）认证 operator 与 Agent；</li>
 *   <li>从签名 registry 解析政策、strategy release/assignment、readiness 当前 head 和撤销状态；</li>
 *   <li>从可信账本、PIT 估值和 instrument specification 重算名义金额与 loss-at-stop；</li>
 *   <li>使用数据库时间将 capability、累计名义/日损/UNKNOWN 预算预占、OMS 初始事实和审计
 *       放入同一事务，并在最终 gateway 前重新验证；</li>
 *   <li>把内核生成的 exact action/command、valuation、risk-budget、recommendation 和 mandate
 *       摘要纳入版本化执行哈希；capability 消费只接受由这些字段派生 useId 的
 *       {@link com.tj.crypto.autonomy.agent.CapabilityUseRequest}，自由字符串 API 永久拒绝；</li>
 *   <li>任何存储、验证器、证据、身份、快照或当前 head 不可用时 fail-closed。</li>
 * </ul>
 *
 * <p>在上述接线与真实环境证据完成前，严禁把本包的 token 或 receipt 交给交易网关。
 */
package com.tj.crypto.autonomy.agent;
