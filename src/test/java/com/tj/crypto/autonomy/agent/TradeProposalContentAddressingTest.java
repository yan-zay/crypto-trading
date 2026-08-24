package com.tj.crypto.autonomy.agent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.tj.crypto.autonomy.agent.AutonomyTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeProposalContentAddressingTest {

    @Test
    void proposalIdMustAddressEverySubstantiveFieldAndRemainDatabaseSafe() {
        TradeProposal original = l3Proposal();
        AgentDecisionLineage changedLineage = new AgentDecisionLineage(
                "model-v1", D9, D3, "tool-policy-v1", D4, D5, D6);
        TradeProposal changed = TradeProposal.create(original.action(), original.governanceContext(),
                original.agentIdentity(), changedLineage, original.strategy(), original.account(),
                original.venue(), original.instrument(), original.direction(), original.proposedNotional(),
                original.settlementCurrency(), original.lossAtStop(), original.policyId(),
                original.policyVersion(), original.policyArtifactSha256(), original.rationaleSha256(),
                original.createdAt(), original.expiresAt());

        assertThat(original.proposalId()).hasSize(64).isEqualTo(original.proposalSha256());
        assertThat(changed.proposalSha256()).isNotEqualTo(original.proposalSha256());
        assertThatThrownBy(() -> new TradeProposal(
                original.proposalId(), original.proposalSha256(), original.action(),
                original.governanceContext(), original.agentIdentity(), original.agentLineage(),
                original.strategy(), original.account(), original.venue(), original.instrument(),
                original.direction(), original.proposedNotional().add(BigDecimal.ONE),
                original.settlementCurrency(), original.lossAtStop(), original.policyId(),
                original.policyVersion(), original.policyArtifactSha256(), original.rationaleSha256(),
                original.createdAt(), original.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete payload");
    }

    @Test
    void instrumentSpecificationAndStrategyAssignmentArePartOfIdentity() {
        TradeProposal original = l3Proposal();
        InstrumentRef changedInstrument = new InstrumentRef(
                original.instrument().instrumentNamespace(), original.instrument().assetClass(),
                original.instrument().instrumentId(), D9);
        StrategyRef changedStrategy = new StrategyRef(original.strategy().strategyId(),
                original.strategy().releaseVersion(), original.strategy().strategyArtifactSha256(),
                original.strategy().runtimeAssignmentId(), "4");
        TradeProposal instrumentChanged = TradeProposal.create(original.action(), original.governanceContext(),
                original.agentIdentity(), original.agentLineage(), original.strategy(), original.account(),
                original.venue(), changedInstrument, original.direction(), original.proposedNotional(),
                original.settlementCurrency(), original.lossAtStop(), original.policyId(),
                original.policyVersion(), original.policyArtifactSha256(), original.rationaleSha256(),
                original.createdAt(), original.expiresAt());
        TradeProposal assignmentChanged = TradeProposal.create(original.action(), original.governanceContext(),
                original.agentIdentity(), original.agentLineage(), changedStrategy, original.account(),
                original.venue(), original.instrument(), original.direction(), original.proposedNotional(),
                original.settlementCurrency(), original.lossAtStop(), original.policyId(),
                original.policyVersion(), original.policyArtifactSha256(), original.rationaleSha256(),
                original.createdAt(), original.expiresAt());

        assertThat(Set.of(original.proposalSha256(), instrumentChanged.proposalSha256(),
                assignmentChanged.proposalSha256())).hasSize(3);
    }

    @Test
    void policyDigestMustAddressAllNormalizedLimitsAndScopes() {
        BoundedAutonomyPolicy original = policy();

        assertThatThrownBy(() -> new BoundedAutonomyPolicy(
                original.policyId(), original.policyVersion(), original.policyArtifactSha256(),
                original.governanceContext(), original.allowedScopes(), original.allowedActions(),
                original.tradingWindows(), new BigDecimal("999999"), original.settlementCurrency(),
                original.maxDailyLoss(), original.maxLossAtStop(), original.maxProposalTtl(),
                original.maxCapabilityTtl(), original.maxLossSnapshotAge(), original.validFrom(),
                original.validUntil()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete normalized policy content");

        BoundedAutonomyPolicy changed = BoundedAutonomyPolicy.create(
                original.policyId(), original.policyVersion(), original.governanceContext(),
                original.allowedScopes(), original.allowedActions(), original.tradingWindows(),
                new BigDecimal("999"), original.settlementCurrency(), original.maxDailyLoss(),
                original.maxLossAtStop(), original.maxProposalTtl(), original.maxCapabilityTtl(),
                original.maxLossSnapshotAge(), original.validFrom(), original.validUntil());
        assertThat(changed.policyArtifactSha256()).isNotEqualTo(original.policyArtifactSha256());
    }

    @Test
    void policyCannotNameFutureOrL3ActionsAndTtlsHaveAbsoluteCeilings() {
        BoundedAutonomyPolicy original = policy();

        assertThatThrownBy(() -> BoundedAutonomyPolicy.create(
                "policy", "v1", GOVERNANCE, original.allowedScopes(),
                Set.of(TradeProposalAction.L3_LIVE_ADVICE), original.tradingWindows(),
                BigDecimal.TEN, "USD", BigDecimal.TEN, BigDecimal.ONE,
                Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofSeconds(10),
                original.validFrom(), original.validUntil()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("select/adjust");
        assertThatThrownBy(() -> BoundedAutonomyPolicy.create(
                "policy", "v1", GOVERNANCE, original.allowedScopes(),
                Set.of(TradeProposalAction.L4_SELECT_PREAPPROVED), original.tradingWindows(),
                BigDecimal.TEN, "USD", BigDecimal.TEN, BigDecimal.ONE,
                Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofSeconds(10),
                original.validFrom(), original.validUntil()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute safety ceiling");
    }
}
