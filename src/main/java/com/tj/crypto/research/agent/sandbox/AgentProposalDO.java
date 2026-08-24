package com.tj.crypto.research.agent.sandbox;

import lombok.Data;

@Data
public class AgentProposalDO {
    private String proposalId;
    private String sessionId;
    private String targetEnvironment;
    private String approvalReference;
    private String approvalArtifactSha256;
    private String approvalVerificationReceiptSha256;
    private String approvedPayloadSha256;
    private String policyVersion;
    private Long createdAtMs;
    private Long expiresAtMs;
    private String status;
    private String proposalJson;
    private String revokedBy;
    private String revokeReason;
    private Long revokedAtMs;
}
