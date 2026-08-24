package com.tj.crypto.autonomy.agent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tj.crypto.autonomy.agent.AutonomyTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityUseRequestTest {

    @Test
    void useIdIsDerivedFromTokenProposalCommandValuationRiskEnvelopeConsumerAndTtl() {
        Fixture fixture = fixture();
        CapabilityUseRequest base = capabilityUse(fixture.token, fixture.proposal, fixture.policy);
        CapabilityUseRequest changedCommand = use(fixture, D1, D6, D3, D4, D5);
        CapabilityUseRequest changedValuation = use(fixture, D1, D2, D6, D4, D5);
        CapabilityUseRequest changedRisk = use(fixture, D1, D2, D3, D6, D5);
        CapabilityUseRequest changedEnvelope = use(fixture, D1, D2, D3, D4, D6);

        assertThat(base.useId()).hasSize(64).isEqualTo(base.useRequestSha256());
        assertThat(Set.of(base.useId(), changedCommand.useId(), changedValuation.useId(),
                changedRisk.useId(), changedEnvelope.useId())).hasSize(5);
        assertThat(base.riskReservationSha256()).isEqualTo(fixture.token.riskReservationSha256());
    }

    @Test
    void callerCannotKeepUseIdWhileReplacingAnyExecutionBinding() {
        Fixture fixture = fixture();
        CapabilityUseRequest base = capabilityUse(fixture.token, fixture.proposal, fixture.policy);

        assertThatThrownBy(() -> new CapabilityUseRequest(
                base.useId(), base.useRequestSha256(), base.audience(), base.requestingOperator(),
                base.consumerIdentity(), base.capabilityTokenId(), base.capabilityTokenSha256(),
                base.deploymentEnvironment(), base.governanceSubjectSha256(), base.proposalId(),
                base.proposalSha256(), base.policyId(), base.policyVersion(), base.policyArtifactSha256(),
                base.executionCandidateSha256(), D7, base.valuationSnapshotSha256(),
                base.riskBudgetSha256(), base.riskReservationSha256(),
                base.orderDecisionEnvelopeSha256(), base.createdAt(), base.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete capability use payload");
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyFreeFormStringUseIdAlwaysFailsClosed() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.consumeCapability(fixture.token, "arbitrary-use",
                "operator", fixture.proposal, fixture.policy, fixture.risk))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("UNBOUND_CAPABILITY_USE_FORBIDDEN");
    }

    @Test
    void useRequestHasIndependentThirtySecondAbsoluteTtlCeiling() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> CapabilityUseRequest.create(fixture.token, fixture.proposal,
                fixture.policy, "operator", fixture.proposal.agentIdentity(), D1, D2, D3, D4, D5,
                NOW, NOW.plusSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("30-second ceiling");
    }

    private static CapabilityUseRequest use(
            Fixture fixture,
            String candidate,
            String command,
            String valuation,
            String risk,
            String envelope) {
        return CapabilityUseRequest.create(fixture.token, fixture.proposal, fixture.policy,
                "operator", fixture.proposal.agentIdentity(), candidate, command, valuation,
                risk, envelope, NOW, NOW.plusSeconds(20));
    }

    private static Fixture fixture() {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal proposal = l4Proposal(policy);
        DailyLossSnapshot risk = lossSnapshot();
        L4BoundedAutonomyService service = new L4BoundedAutonomyService(new ExactVerifier(),
                new InMemoryRevocationStore(), new InMemoryAuthorizationTokenStore(),
                new InMemoryCapabilityTokenStore(), CLOCK,
                () -> "authorization-use", () -> "capability-use-fixture");
        L4CapabilityToken token = service.authorize(proposal, "operator", policy, risk,
                reference(AuthorizationKind.PREAPPROVED_L4_POLICY_GRANT, proposal, "operator",
                        risk.snapshotSha256(), "use-fixture-grant"));
        return new Fixture(policy, proposal, risk, service, token);
    }

    private record Fixture(
            BoundedAutonomyPolicy policy,
            TradeProposal proposal,
            DailyLossSnapshot risk,
            L4BoundedAutonomyService service,
            L4CapabilityToken token) {
    }
}
