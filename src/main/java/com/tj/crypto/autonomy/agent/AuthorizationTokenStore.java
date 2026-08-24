package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 跨节点持久、原子的一次性授权 token 消费/撤销 port。 */
public interface AuthorizationTokenStore {

    void claim(AuthorizationTokenClaim claim);

    void revoke(String tokenId, String reason, Instant revokedAt);
}
