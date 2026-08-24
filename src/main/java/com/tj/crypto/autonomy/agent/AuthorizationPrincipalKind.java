package com.tj.crypto.autonomy.agent;

/** 由可信 verifier 认证的签发主体类型，不能由 Agent 自报。 */
public enum AuthorizationPrincipalKind {
    HUMAN_APPROVER,
    POLICY_AUTHORITY
}
