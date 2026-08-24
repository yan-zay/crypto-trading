package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/**
 * 单次能力消费的不可执行审计回执。
 *
 * <p>构造器仅包内可见，外部调用方不能伪造；该类型不是下游凭证，也没有订单或 gateway 指令。
 */
public final class CapabilityConsumption {

    private final String tokenId;
    private final String tokenSha256;
    private final String useId;
    private final String useRequestSha256;
    private final String audience;
    private final String consumerIdentity;
    private final String deploymentEnvironment;
    private final String governanceSubjectSha256;
    private final String proposalId;
    private final String proposalSha256;
    private final String policyId;
    private final String policyVersion;
    private final String policyArtifactSha256;
    private final String requestingOperator;
    private final String executionCandidateSha256;
    private final String commandSha256;
    private final String valuationSnapshotSha256;
    private final String riskBudgetSha256;
    private final String riskReservationSha256;
    private final String orderDecisionEnvelopeSha256;
    private final String consumerVerificationReceiptSha256;
    private final Instant useCreatedAt;
    private final Instant useExpiresAt;
    private final Instant consumedAt;
    private final String consumptionReceiptSha256;

    CapabilityConsumption(
            String tokenId,
            CapabilityUseRequest useRequest,
            String proposalId,
            String proposalSha256,
            String policyId,
            String policyVersion,
            String requestingOperator,
            String consumerVerificationReceiptSha256,
            Instant consumedAt,
            String consumptionReceiptSha256) {
        this.tokenId = AutonomyValues.text(tokenId, "tokenId");
        if (useRequest == null) throw new IllegalArgumentException("useRequest is required");
        if (!this.tokenId.equals(useRequest.capabilityTokenId())) {
            throw new IllegalArgumentException("tokenId does not match useRequest");
        }
        this.tokenSha256 = useRequest.capabilityTokenSha256();
        this.useId = useRequest.useId();
        this.useRequestSha256 = useRequest.useRequestSha256();
        this.audience = useRequest.audience();
        this.consumerIdentity = useRequest.consumerIdentity();
        this.deploymentEnvironment = useRequest.deploymentEnvironment();
        this.governanceSubjectSha256 = useRequest.governanceSubjectSha256();
        this.proposalId = AutonomyValues.text(proposalId, "proposalId");
        this.proposalSha256 = AutonomyValues.digest(proposalSha256, "proposalSha256");
        this.policyId = AutonomyValues.text(policyId, "policyId");
        this.policyVersion = AutonomyValues.text(policyVersion, "policyVersion");
        this.policyArtifactSha256 = useRequest.policyArtifactSha256();
        this.requestingOperator = AutonomyValues.text(requestingOperator, "requestingOperator");
        if (!this.proposalId.equals(useRequest.proposalId())
                || !this.proposalSha256.equals(useRequest.proposalSha256())
                || !this.policyId.equals(useRequest.policyId())
                || !this.policyVersion.equals(useRequest.policyVersion())
                || !this.requestingOperator.equals(useRequest.requestingOperator())) {
            throw new IllegalArgumentException("consumption fields do not match useRequest");
        }
        this.executionCandidateSha256 = useRequest.executionCandidateSha256();
        this.commandSha256 = useRequest.commandSha256();
        this.valuationSnapshotSha256 = useRequest.valuationSnapshotSha256();
        this.riskBudgetSha256 = useRequest.riskBudgetSha256();
        this.riskReservationSha256 = useRequest.riskReservationSha256();
        this.orderDecisionEnvelopeSha256 = useRequest.orderDecisionEnvelopeSha256();
        this.consumerVerificationReceiptSha256 = AutonomyValues.digest(
                consumerVerificationReceiptSha256, "consumerVerificationReceiptSha256");
        this.useCreatedAt = useRequest.createdAt();
        this.useExpiresAt = useRequest.expiresAt();
        if (consumedAt == null) throw new IllegalArgumentException("consumedAt is required");
        this.consumedAt = consumedAt;
        this.consumptionReceiptSha256 = AutonomyValues.digest(
                consumptionReceiptSha256, "consumptionReceiptSha256");
        String expectedReceipt = AutonomyValues.sha256(sink -> sink
                .value("agent-l4-local-consumption-receipt-v2")
                .value(this.tokenSha256)
                .value(this.useRequestSha256)
                .value(this.consumerVerificationReceiptSha256)
                .value(this.consumedAt));
        if (!this.consumptionReceiptSha256.equals(expectedReceipt)) {
            throw new IllegalArgumentException(
                    "consumptionReceiptSha256 must bind token, complete use, verifier and time");
        }
    }

    public String tokenId() { return tokenId; }
    public String tokenSha256() { return tokenSha256; }
    public String useId() { return useId; }
    public String useRequestSha256() { return useRequestSha256; }
    public String audience() { return audience; }
    public String consumerIdentity() { return consumerIdentity; }
    public String deploymentEnvironment() { return deploymentEnvironment; }
    public String governanceSubjectSha256() { return governanceSubjectSha256; }
    public String proposalId() { return proposalId; }
    public String proposalSha256() { return proposalSha256; }
    public String policyId() { return policyId; }
    public String policyVersion() { return policyVersion; }
    public String policyArtifactSha256() { return policyArtifactSha256; }
    public String requestingOperator() { return requestingOperator; }
    public String executionCandidateSha256() { return executionCandidateSha256; }
    public String commandSha256() { return commandSha256; }
    public String valuationSnapshotSha256() { return valuationSnapshotSha256; }
    public String riskBudgetSha256() { return riskBudgetSha256; }
    public String riskReservationSha256() { return riskReservationSha256; }
    public String orderDecisionEnvelopeSha256() { return orderDecisionEnvelopeSha256; }
    public String consumerVerificationReceiptSha256() { return consumerVerificationReceiptSha256; }
    public Instant useCreatedAt() { return useCreatedAt; }
    public Instant useExpiresAt() { return useExpiresAt; }
    public Instant consumedAt() { return consumedAt; }
    public String consumptionReceiptSha256() { return consumptionReceiptSha256; }
}
