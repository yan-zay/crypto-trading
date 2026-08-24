package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 未接数据库原子防重放存储时默认拒绝。 */
public final class RejectingAuthorizationTokenStore implements AuthorizationTokenStore {
    @Override
    public void claim(AuthorizationTokenClaim claim) {
        throw unavailable();
    }

    @Override
    public void revoke(String tokenId, String reason, Instant revokedAt) {
        throw unavailable();
    }

    private static AgentAutonomyViolationException unavailable() {
        return new AgentAutonomyViolationException(
                "AUTHORIZATION_TOKEN_STORE_NOT_WIRED",
                "A cross-node atomic authorization token store is not wired");
    }
}
