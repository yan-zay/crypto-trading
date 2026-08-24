package com.tj.crypto.trading.venue;

import java.util.Optional;

public interface LiveGovernanceDecisionStore {
    void append(LiveGovernanceDecision decision);
    Optional<LiveGovernanceDecision> find(String decisionId);

    static LiveGovernanceDecisionStore unavailable() {
        return new LiveGovernanceDecisionStore() {
            @Override public void append(LiveGovernanceDecision decision) {
                throw new IllegalStateException("live governance decision store is unavailable");
            }
            @Override public Optional<LiveGovernanceDecision> find(String decisionId) {
                return Optional.empty();
            }
        };
    }
}
