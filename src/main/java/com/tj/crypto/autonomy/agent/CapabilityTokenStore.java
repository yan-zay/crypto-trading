package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 跨节点持久、原子的 L4 capability 签发、消费与撤销 port。 */
public interface CapabilityTokenStore {

    /**
     * 原子验证 risk snapshot head、累计预算和数据库时间，并同时写入 capability 与预算预占。
     */
    void issue(L4CapabilityToken token, CapabilityBudgetReservation reservation);

    /**
     * 使用数据库时间原子检查 token/use active、TTL、撤销和一次性状态；持久记录完整 use payload，
     * 包括 command、valuation、risk budget/reservation 与 order-decision-envelope 摘要。
     * 任一绑定不一致必须拒绝，UNKNOWN 不得释放预占。
     */
    void consume(L4CapabilityToken token, CapabilityUseRequest useRequest, Instant consumedAt);

    void revoke(String tokenId, String reason, Instant revokedAt);
}
