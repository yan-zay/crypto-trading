package com.tj.crypto.autonomy.agent;

/** 未接外部可信审批/政策设施时的默认 fail-closed verifier。 */
public final class RejectingTrustedAuthorizationVerifier implements TrustedAuthorizationVerifier {
    @Override
    public VerifiedAuthorization verifyAndConsume(AuthorizationVerificationRequest request) {
        throw new AgentAutonomyViolationException(
                "TRUSTED_AUTHORIZATION_NOT_WIRED",
                "Trusted approval and policy authorization verification is not wired");
    }
}
