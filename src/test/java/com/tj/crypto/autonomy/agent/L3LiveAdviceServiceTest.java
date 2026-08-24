package com.tj.crypto.autonomy.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.tj.crypto.autonomy.agent.AutonomyTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L3LiveAdviceServiceTest {

    @Test
    void independentHumanApprovalProducesOnlyOpaqueNonExecutableAdvice() {
        InMemoryRevocationStore revocations = new InMemoryRevocationStore();
        L3LiveAdviceService service = new L3LiveAdviceService(new ExactVerifier(), revocations,
                new InMemoryAuthorizationTokenStore(), CLOCK, Duration.ofMinutes(5), () -> "use-l3-1");
        TradeProposal proposal = l3Proposal();

        ApprovedLiveAdvice advice = service.approve(proposal, "requesting-operator",
                reference(AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL, proposal,
                        "requesting-operator", AuthorizationReference.EMPTY_CONTEXT_SHA256,
                        "human-approval-1"));

        assertThat(advice.proposal()).isSameAs(proposal);
        assertThat(advice.humanApprover()).isEqualTo("independent-human-reviewer");
        assertThat(advice.expiresAt()).isEqualTo(proposal.expiresAt());
    }

    @Test
    void defaultServiceFailsClosedWhenDurableTrustAndRevocationAreNotWired() {
        TradeProposal proposal = l3Proposal();
        L3LiveAdviceService service = new L3LiveAdviceService();

        assertThatThrownBy(() -> service.approve(proposal, "operator",
                reference(AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL, proposal, "operator",
                        AuthorizationReference.EMPTY_CONTEXT_SHA256, "approval")))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("PROPOSAL_REVOCATION_STORE_NOT_WIRED");
    }

    @Test
    void approvalTokenIsSingleUseAndProposalsAndTokensCanBeRevoked() {
        TradeProposal proposal = l3Proposal();
        InMemoryRevocationStore revocations = new InMemoryRevocationStore();
        InMemoryAuthorizationTokenStore tokens = new InMemoryAuthorizationTokenStore();
        L3LiveAdviceService service = new L3LiveAdviceService(new ExactVerifier(), revocations,
                tokens, CLOCK, Duration.ofMinutes(5), () -> "use-l3-replay");
        AuthorizationReference approval = reference(
                AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL, proposal, "operator",
                AuthorizationReference.EMPTY_CONTEXT_SHA256, "human-token-replay");

        service.approve(proposal, "operator", approval);
        assertThatThrownBy(() -> service.approve(proposal, "operator", approval))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("AUTHORIZATION_REPLAY");

        TradeProposal revokedProposal = TradeProposal.create(proposal.action(), proposal.governanceContext(),
                proposal.agentIdentity(), proposal.agentLineage(), proposal.strategy(), proposal.account(),
                proposal.venue(), proposal.instrument(), TradeDirection.REDUCE_LONG,
                proposal.proposedNotional(), proposal.settlementCurrency(), proposal.lossAtStop(),
                proposal.policyId(), proposal.policyVersion(), proposal.policyArtifactSha256(), D8,
                proposal.createdAt(), proposal.expiresAt());
        service.revokeProposal(revokedProposal.proposalId(), "operator", "incident");
        assertThatThrownBy(() -> service.approve(revokedProposal, "operator",
                reference(AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL, revokedProposal, "operator",
                        AuthorizationReference.EMPTY_CONTEXT_SHA256, "other-token")))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("PROPOSAL_REVOKED");

        TradeProposal tokenRevokedProposal = TradeProposal.create(proposal.action(), proposal.governanceContext(),
                proposal.agentIdentity(), proposal.agentLineage(), proposal.strategy(), proposal.account(),
                proposal.venue(), proposal.instrument(), TradeDirection.REDUCE_SHORT,
                proposal.proposedNotional(), proposal.settlementCurrency(), proposal.lossAtStop(),
                proposal.policyId(), proposal.policyVersion(), proposal.policyArtifactSha256(), D7,
                proposal.createdAt(), proposal.expiresAt());
        service.revokeApprovalToken("revoked-token", "security event");
        assertThatThrownBy(() -> service.approve(tokenRevokedProposal, "operator",
                reference(AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL, tokenRevokedProposal, "operator",
                        AuthorizationReference.EMPTY_CONTEXT_SHA256, "revoked-token")))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("AUTHORIZATION_REPLAY");
    }

    @Test
    void operatorAgentAudiencePolicyAndIndependentHumanBindingsAreExact() {
        TradeProposal proposal = l3Proposal();
        L3LiveAdviceService service = new L3LiveAdviceService(new ExactVerifier(),
                new InMemoryRevocationStore(), new InMemoryAuthorizationTokenStore(), CLOCK,
                Duration.ofMinutes(5), () -> "use-l3-binding");

        AuthorizationReference wrongOperator = reference(
                AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL, proposal, "alice",
                AuthorizationReference.EMPTY_CONTEXT_SHA256, "binding-1");
        assertThatThrownBy(() -> service.approve(proposal, "mallory", wrongOperator))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("AUTHORIZATION_OPERATOR_MISMATCH");

        AuthorizationReference selfApproval = new AuthorizationReference(
                "binding-2", D5, AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL,
                TrustedAuthorizationVerifier.L3_AUDIENCE, "operator", proposal.agentIdentity(),
                proposal.governanceContext().deploymentEnvironment(),
                proposal.governanceContext().governanceSubjectSha256(), proposal.proposalId(),
                proposal.proposalSha256(), proposal.policyId(), proposal.policyVersion(),
                proposal.policyArtifactSha256(), AuthorizationReference.EMPTY_CONTEXT_SHA256,
                proposal.agentIdentity(), proposal.expiresAt());
        L3LiveAdviceService independentService = new L3LiveAdviceService(new ExactVerifier(),
                new InMemoryRevocationStore(), new InMemoryAuthorizationTokenStore(), CLOCK,
                Duration.ofMinutes(5), () -> "use-l3-self");
        assertThatThrownBy(() -> independentService.approve(proposal, "operator", selfApproval))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("AUTHORIZATION_NOT_INDEPENDENT");
    }

    @Test
    void l3RejectsL4ActionsAndLongOrExpiredAdvice() {
        BoundedAutonomyPolicy policy = policy();
        TradeProposal l4 = l4Proposal(policy);
        L3LiveAdviceService service = new L3LiveAdviceService(new ExactVerifier(),
                new InMemoryRevocationStore(), new InMemoryAuthorizationTokenStore(), CLOCK,
                Duration.ofMinutes(5), () -> "use");

        assertThatThrownBy(() -> service.approve(l4, "operator",
                reference(AuthorizationKind.INDEPENDENT_HUMAN_APPROVAL, l4, "operator",
                        AuthorizationReference.EMPTY_CONTEXT_SHA256, "token")))
                .isInstanceOf(AgentAutonomyViolationException.class)
                .extracting("reasonCode").isEqualTo("L3_ACTION_NOT_ALLOWED");
    }
}
