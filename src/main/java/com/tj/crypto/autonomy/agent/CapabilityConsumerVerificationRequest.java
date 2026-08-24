package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 交给可信身份边界认证能力消费人的完整请求。 */
public record CapabilityConsumerVerificationRequest(
        String useId,
        String useRequestSha256,
        String audience,
        String claimedOperator,
        String claimedConsumerIdentity,
        String capabilityTokenId,
        String capabilityTokenSha256,
        String deploymentEnvironment,
        String governanceSubjectSha256,
        String proposalId,
        String proposalSha256,
        String policyId,
        String policyVersion,
        String policyArtifactSha256,
        String executionCandidateSha256,
        String commandSha256,
        String valuationSnapshotSha256,
        String riskBudgetSha256,
        String riskReservationSha256,
        String orderDecisionEnvelopeSha256,
        Instant useCreatedAt,
        Instant useExpiresAt) {

    public CapabilityConsumerVerificationRequest {
        useId = AutonomyValues.digest(useId, "useId");
        useRequestSha256 = AutonomyValues.digest(useRequestSha256, "useRequestSha256");
        audience = AutonomyValues.text(audience, "audience");
        claimedOperator = AutonomyValues.text(claimedOperator, "claimedOperator");
        claimedConsumerIdentity = AutonomyValues.text(
                claimedConsumerIdentity, "claimedConsumerIdentity");
        capabilityTokenId = AutonomyValues.text(capabilityTokenId, "capabilityTokenId");
        capabilityTokenSha256 = AutonomyValues.digest(capabilityTokenSha256, "capabilityTokenSha256");
        deploymentEnvironment = AutonomyValues.text(
                deploymentEnvironment, "deploymentEnvironment").toUpperCase(java.util.Locale.ROOT);
        governanceSubjectSha256 = AutonomyValues.digest(
                governanceSubjectSha256, "governanceSubjectSha256");
        proposalId = AutonomyValues.text(proposalId, "proposalId");
        proposalSha256 = AutonomyValues.digest(proposalSha256, "proposalSha256");
        policyId = AutonomyValues.text(policyId, "policyId");
        policyVersion = AutonomyValues.text(policyVersion, "policyVersion");
        policyArtifactSha256 = AutonomyValues.digest(policyArtifactSha256, "policyArtifactSha256");
        executionCandidateSha256 = AutonomyValues.digest(
                executionCandidateSha256, "executionCandidateSha256");
        commandSha256 = AutonomyValues.digest(commandSha256, "commandSha256");
        valuationSnapshotSha256 = AutonomyValues.digest(
                valuationSnapshotSha256, "valuationSnapshotSha256");
        riskBudgetSha256 = AutonomyValues.digest(riskBudgetSha256, "riskBudgetSha256");
        riskReservationSha256 = AutonomyValues.digest(
                riskReservationSha256, "riskReservationSha256");
        orderDecisionEnvelopeSha256 = AutonomyValues.digest(
                orderDecisionEnvelopeSha256, "orderDecisionEnvelopeSha256");
        if (useCreatedAt == null || useExpiresAt == null || !useExpiresAt.isAfter(useCreatedAt)) {
            throw new IllegalArgumentException("use timestamps must define a positive lifetime");
        }
    }

    public static CapabilityConsumerVerificationRequest from(CapabilityUseRequest use) {
        if (use == null) throw new IllegalArgumentException("use is required");
        return new CapabilityConsumerVerificationRequest(use.useId(), use.useRequestSha256(),
                use.audience(), use.requestingOperator(), use.consumerIdentity(),
                use.capabilityTokenId(), use.capabilityTokenSha256(), use.deploymentEnvironment(),
                use.governanceSubjectSha256(), use.proposalId(), use.proposalSha256(),
                use.policyId(), use.policyVersion(), use.policyArtifactSha256(),
                use.executionCandidateSha256(), use.commandSha256(),
                use.valuationSnapshotSha256(), use.riskBudgetSha256(),
                use.riskReservationSha256(), use.orderDecisionEnvelopeSha256(),
                use.createdAt(), use.expiresAt());
    }
}
