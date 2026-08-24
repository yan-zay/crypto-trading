package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 未接数据库原子 capability ledger 时默认拒绝。 */
public final class RejectingCapabilityTokenStore implements CapabilityTokenStore {
    @Override
    public void issue(L4CapabilityToken token, CapabilityBudgetReservation reservation) {
        throw unavailable();
    }

    @Override
    public void consume(
            L4CapabilityToken token, CapabilityUseRequest useRequest, Instant consumedAt) {
        throw unavailable();
    }

    @Override
    public void revoke(String tokenId, String reason, Instant revokedAt) {
        throw unavailable();
    }

    private static AgentAutonomyViolationException unavailable() {
        return new AgentAutonomyViolationException(
                "CAPABILITY_TOKEN_STORE_NOT_WIRED",
                "A cross-node atomic capability token store is not wired");
    }
}
