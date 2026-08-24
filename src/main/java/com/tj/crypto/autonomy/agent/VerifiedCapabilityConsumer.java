package com.tj.crypto.autonomy.agent;

import java.time.Instant;

/** 来自可信认证设施的能力消费主体回执。 */
public record VerifiedCapabilityConsumer(
        String authenticatedOperator,
        String authenticatedConsumerIdentity,
        String useRequestSha256,
        String audience,
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
        Instant useExpiresAt,
        String consumedUseId,
        String verificationReceiptSha256) {

    public VerifiedCapabilityConsumer {
        authenticatedOperator = AutonomyValues.text(authenticatedOperator, "authenticatedOperator");
        authenticatedConsumerIdentity = AutonomyValues.text(
                authenticatedConsumerIdentity, "authenticatedConsumerIdentity");
        useRequestSha256 = AutonomyValues.digest(useRequestSha256, "useRequestSha256");
        audience = AutonomyValues.text(audience, "audience");
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
        consumedUseId = AutonomyValues.digest(consumedUseId, "consumedUseId");
        verificationReceiptSha256 = AutonomyValues.digest(
                verificationReceiptSha256, "verificationReceiptSha256");
    }

    public void requireExactMatch(CapabilityConsumerVerificationRequest request) {
        if (request == null) throw violation("CAPABILITY_CONSUMER_REQUEST_MISSING", "Consumer request is missing");
        if (!authenticatedOperator.equals(request.claimedOperator())) {
            throw violation("CAPABILITY_CONSUMER_IDENTITY_MISMATCH", "Authenticated operator does not match");
        }
        if (!authenticatedConsumerIdentity.equals(request.claimedConsumerIdentity())) {
            throw violation("CAPABILITY_CONSUMER_AGENT_IDENTITY_MISMATCH",
                    "Authenticated Agent workload does not match");
        }
        if (!useRequestSha256.equals(request.useRequestSha256())) {
            throw violation("CAPABILITY_CONSUMER_USE_REQUEST_MISMATCH",
                    "Consumer verification binds another content-addressed use request");
        }
        if (!audience.equals(request.audience())) {
            throw violation("CAPABILITY_CONSUMER_AUDIENCE_MISMATCH", "Consumer audience does not match");
        }
        if (!capabilityTokenId.equals(request.capabilityTokenId())
                || !capabilityTokenSha256.equals(request.capabilityTokenSha256())) {
            throw violation("CAPABILITY_CONSUMER_TOKEN_MISMATCH", "Consumer verification binds another token");
        }
        if (!deploymentEnvironment.equals(request.deploymentEnvironment())
                || !governanceSubjectSha256.equals(request.governanceSubjectSha256())) {
            throw violation("CAPABILITY_CONSUMER_GOVERNANCE_CONTEXT_MISMATCH",
                    "Consumer verification binds another environment or governance subject");
        }
        if (!proposalId.equals(request.proposalId()) || !proposalSha256.equals(request.proposalSha256())) {
            throw violation("CAPABILITY_CONSUMER_PROPOSAL_MISMATCH", "Consumer verification binds another proposal");
        }
        if (!policyId.equals(request.policyId()) || !policyVersion.equals(request.policyVersion())
                || !policyArtifactSha256.equals(request.policyArtifactSha256())) {
            throw violation("CAPABILITY_CONSUMER_POLICY_MISMATCH", "Consumer verification binds another policy");
        }
        if (!executionCandidateSha256.equals(request.executionCandidateSha256())
                || !commandSha256.equals(request.commandSha256())) {
            throw violation("CAPABILITY_CONSUMER_EXECUTION_CANDIDATE_MISMATCH",
                    "Consumer verification binds another execution candidate or command");
        }
        if (!valuationSnapshotSha256.equals(request.valuationSnapshotSha256())) {
            throw violation("CAPABILITY_CONSUMER_VALUATION_MISMATCH",
                    "Consumer verification binds another valuation snapshot");
        }
        if (!riskBudgetSha256.equals(request.riskBudgetSha256())
                || !riskReservationSha256.equals(request.riskReservationSha256())) {
            throw violation("CAPABILITY_CONSUMER_RISK_MISMATCH",
                    "Consumer verification binds another risk budget or reservation");
        }
        if (!orderDecisionEnvelopeSha256.equals(request.orderDecisionEnvelopeSha256())) {
            throw violation("CAPABILITY_CONSUMER_ENVELOPE_MISMATCH",
                    "Consumer verification binds another order decision envelope");
        }
        if (!useCreatedAt.equals(request.useCreatedAt()) || !useExpiresAt.equals(request.useExpiresAt())) {
            throw violation("CAPABILITY_CONSUMER_TTL_MISMATCH",
                    "Consumer verification binds another use lifetime");
        }
        if (!consumedUseId.equals(request.useId())) {
            throw violation("CAPABILITY_CONSUMER_USE_MISMATCH", "Consumer verification binds another use");
        }
    }

    private static AgentAutonomyViolationException violation(String code, String message) {
        return new AgentAutonomyViolationException(code, message);
    }
}
