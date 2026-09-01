package com.tj.crypto.research.agent.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.governance.evidence.GovernanceSubject;
import com.tj.crypto.research.agent.policy.AgentApprovalReference;
import com.tj.crypto.research.agent.policy.AgentArtifactVersions;
import com.tj.crypto.research.agent.policy.AgentProposalTarget;
import com.tj.crypto.strategy.core.StrategyManager;
import com.tj.crypto.strategy.lifecycle.SignedStrategyArtifact;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStage;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStore;
import com.tj.crypto.strategy.lifecycle.StrategyPromotionGate;
import com.tj.crypto.strategy.lifecycle.StrategyReleaseAssignment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentProposalEvaluationServiceTest {

    @Test
    void consumesExactShadowProposalIntoDurableBoundedRuntimeScope() throws Exception {
        AgentProposalMapper mapper = mock(AgentProposalMapper.class);
        StrategyLifecycleStore lifecycle = mock(StrategyLifecycleStore.class);
        StrategyPromotionGate promotion = mock(StrategyPromotionGate.class);
        StrategyManager manager = mock(StrategyManager.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.ofEpochMilli(10_000);
        String artifactSha = "a".repeat(64);
        PaperShadowProposal proposal = proposal(now, artifactSha);
        AgentProposalDO row = new AgentProposalDO();
        row.setProposalId(proposal.proposalId());
        row.setExpiresAtMs(proposal.expiresAt().toEpochMilli());
        row.setProposalJson(json.writeValueAsString(proposal));
        when(mapper.selectConsumable(proposal.proposalId(), now.toEpochMilli())).thenReturn(row);
        when(lifecycle.findArtifact(artifactSha)).thenReturn(Optional.of(artifact(now, artifactSha)));
        when(lifecycle.findReleaseAssignment("MacdCross"))
                .thenReturn(Optional.of(release(now, artifactSha)));
        when(promotion.allows("MacdCross", artifactSha, StrategyLifecycleStage.SHADOW))
                .thenReturn(true);
        List<AgentProposalEvaluationUseDO> uses = new ArrayList<>();
        doAnswer(invocation -> {
            AgentProposalEvaluationUseDO use = invocation.getArgument(0);
            use.setProposalJson(row.getProposalJson());
            uses.add(use);
            return 1;
        }).when(mapper).insertEvaluationUse(any());
        when(mapper.selectActiveEvaluationUses(anyLong())).thenAnswer(ignored -> List.copyOf(uses));
        AgentProposalEvaluationService service = new AgentProposalEvaluationService(
                mapper, lifecycle, promotion, manager, json,
                Clock.fixed(now, ZoneOffset.UTC));

        AgentEvaluationActivation activation = service.consume(proposal.proposalId(), "alice");

        assertThat(activation.target()).isEqualTo(AgentProposalTarget.SHADOW);
        assertThat(activation.instrumentScope()).containsExactly("binance:perpetual:BTCUSDT");
        assertThat(activation.liveExecutionAuthorized()).isFalse();
        ArgumentCaptor<List<AgentEvaluationActivation>> active = ArgumentCaptor.forClass(List.class);
        verify(manager).replaceAgentEvaluationScopes(active.capture());
        assertThat(active.getValue()).containsExactly(activation);
    }

    private PaperShadowProposal proposal(Instant now, String artifactSha) {
        AgentApprovalReference approval = new AgentApprovalReference(
                "approval-1", "b".repeat(64), "c".repeat(64), "bob",
                now.minusSeconds(1), now.plusSeconds(600), AgentProposalTarget.SHADOW);
        AgentArtifactVersions versions = new AgentArtifactVersions(
                "d".repeat(40), "e".repeat(64), "sha256:" + "f".repeat(64),
                "dataset-1", "1".repeat(64), "config-v1", "strategy-v1",
                "agent-v1", "model-v1", "prompt-v1", "policy-v1", 42);
        return new PaperShadowProposal("proposal-1", "session-1", AgentProposalTarget.SHADOW,
                "hypothesis-1", "plan-1", artifactSha,
                List.of("binance:perpetual:BTCUSDT"), "observe signals",
                approval, "2".repeat(64), versions, now, now.plusSeconds(300),
                PaperShadowProposal.ProposalStatus.SUBMITTED);
    }

    private SignedStrategyArtifact artifact(Instant now, String sha) {
        return new SignedStrategyArtifact("MacdCross", "strategy-v1", new byte[]{1}, sha,
                "application/java-archive", "urn:artifact", "Ed25519", "key-1", "AA==",
                now.minusSeconds(10), now.plusSeconds(1_000), "alice", now.minusSeconds(10),
                null, null);
    }

    private StrategyReleaseAssignment release(Instant now, String artifactSha) {
        GovernanceSubject subject = new GovernanceSubject("paper-shadow", "PAPER",
                "paper-1", "3".repeat(40), "4".repeat(64),
                "sha256:" + "5".repeat(64), "6".repeat(64), "7".repeat(64),
                artifactSha, "kms://key", "run-1", "policy-v1");
        return new StrategyReleaseAssignment("assignment-1", "MacdCross", artifactSha,
                StrategyLifecycleStage.SHADOW, subject, now.plusSeconds(600), 1,
                "alice", now.minusSeconds(5));
    }
}
