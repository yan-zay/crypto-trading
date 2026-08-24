package com.tj.crypto.trading.venue;

import java.util.concurrent.atomic.AtomicBoolean;

/** Opaque short-lived permit. Only package orchestration can mint one; gateways can only verify it. */
public final class LiveOrderAuthorization {
    private final String authorizationId;
    private final LiveMutationKind kind;
    private final String commandSha256;
    private final long issuedAtMs;
    private final long expiresAtMs;
    private final LiveGovernanceDecision governanceDecision;
    private final AtomicBoolean consumed = new AtomicBoolean();

    LiveOrderAuthorization(String authorizationId, LiveMutationKind kind,
                           String commandSha256, long issuedAtMs, long expiresAtMs,
                           LiveGovernanceDecision governanceDecision) {
        if (authorizationId == null || authorizationId.isBlank() || kind == null
                || commandSha256 == null || commandSha256.length() != 64
                || issuedAtMs <= 0 || expiresAtMs <= issuedAtMs) {
            throw new IllegalArgumentException("complete live order authorization is required");
        }
        if ((kind == LiveMutationKind.LIVE_RISK_INCREASE) != (governanceDecision != null)) {
            throw new IllegalArgumentException("only LIVE risk authorization carries a governance decision");
        }
        this.authorizationId = authorizationId;
        this.kind = kind;
        this.commandSha256 = commandSha256;
        this.issuedAtMs = issuedAtMs;
        this.expiresAtMs = expiresAtMs;
        this.governanceDecision = governanceDecision;
    }

    public String authorizationId() { return authorizationId; }
    LiveMutationKind kind() { return kind; }
    public String commandSha256() { return commandSha256; }
    public long issuedAtMs() { return issuedAtMs; }
    public long expiresAtMs() { return expiresAtMs; }
    public LiveGovernanceDecision governanceDecision() { return governanceDecision; }

    public boolean liveGoverned() {
        return kind == LiveMutationKind.LIVE_RISK_INCREASE;
    }

    boolean consumed() {
        return consumed.get();
    }

    void consumeOnce() {
        if (!consumed.compareAndSet(false, true)) {
            throw new LiveAuthorizationException("Venue mutation permit was already consumed");
        }
    }
}
