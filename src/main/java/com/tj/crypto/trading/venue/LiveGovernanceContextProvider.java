package com.tj.crypto.trading.venue;

/** Server-side release resolver. Request JSON is never a trusted governance-subject source. */
@FunctionalInterface
interface LiveGovernanceContextProvider {
    ResolvedLiveGovernanceContext requireCurrentCanary(String strategyName, long nowMs);
}
