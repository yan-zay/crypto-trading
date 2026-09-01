package com.tj.crypto.research.agent.sandbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.research.agent.policy.AgentProposalTarget;
import com.tj.crypto.strategy.core.StrategyManager;
import com.tj.crypto.strategy.lifecycle.SignedStrategyArtifact;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStage;
import com.tj.crypto.strategy.lifecycle.StrategyLifecycleStore;
import com.tj.crypto.strategy.lifecycle.StrategyPromotionGate;
import com.tj.crypto.strategy.lifecycle.StrategyReleaseAssignment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Consumes one approved L2 proposal into a bounded Paper/Shadow runtime scope, never live. */
@Service
public class AgentProposalEvaluationService {
    private final AgentProposalMapper mapper;
    private final StrategyLifecycleStore lifecycleStore;
    private final StrategyPromotionGate promotionGate;
    private final StrategyManager strategyManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AgentProposalEvaluationService(AgentProposalMapper mapper,
                                          StrategyLifecycleStore lifecycleStore,
                                          StrategyPromotionGate promotionGate,
                                          StrategyManager strategyManager,
                                          ObjectMapper objectMapper) {
        this(mapper, lifecycleStore, promotionGate, strategyManager,
                objectMapper, Clock.systemUTC());
    }

    AgentProposalEvaluationService(AgentProposalMapper mapper,
                                   StrategyLifecycleStore lifecycleStore,
                                   StrategyPromotionGate promotionGate,
                                   StrategyManager strategyManager,
                                   ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.lifecycleStore = lifecycleStore;
        this.promotionGate = promotionGate;
        this.strategyManager = strategyManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AgentEvaluationActivation consume(String proposalId, String operator) {
        long nowMs = clock.millis();
        AgentProposalDO row = mapper.selectConsumable(required(proposalId, "proposalId"), nowMs);
        if (row == null) throw new IllegalStateException("Proposal is missing, expired, revoked or consumed");
        PaperShadowProposal proposal = parse(row.getProposalJson());
        if (!proposal.proposalId().equals(row.getProposalId())
                || !proposal.expiresAt().equals(Instant.ofEpochMilli(row.getExpiresAtMs()))) {
            throw new IllegalStateException("Proposal JSON does not match its durable envelope");
        }
        SignedStrategyArtifact artifact = lifecycleStore.findArtifact(proposal.strategyArtifactId())
                .orElseThrow(() -> new IllegalStateException("Signed strategy artifact is missing"));
        StrategyReleaseAssignment release = lifecycleStore.findReleaseAssignment(artifact.strategyName())
                .orElseThrow(() -> new IllegalStateException("Current strategy release assignment is missing"));
        if (!release.artifactSha256().equals(artifact.artifactSha256())
                || !release.currentAt(clock.instant())) {
            throw new IllegalStateException("Proposal is not bound to the current strategy release");
        }
        StrategyLifecycleStage requiredStage = proposal.target() == AgentProposalTarget.SHADOW
                ? StrategyLifecycleStage.SHADOW : StrategyLifecycleStage.CANARY;
        if ((proposal.target() == AgentProposalTarget.SHADOW
                && release.minimumStage() != StrategyLifecycleStage.SHADOW)
                || (proposal.target() == AgentProposalTarget.PAPER
                && release.minimumStage() == StrategyLifecycleStage.SHADOW)) {
            throw new IllegalStateException("Proposal target disagrees with runtime release stage");
        }
        if (!promotionGate.allows(artifact.strategyName(), artifact.artifactSha256(), requiredStage)) {
            throw new IllegalStateException("Strategy lifecycle gate rejected the proposal target");
        }
        Set<String> scope = normalizeScope(proposal.instrumentScope());
        String scopeSha = sha256(String.join("\n", scope.stream().sorted().toList()));
        String useSha = sha256("agent-proposal-use-v1|" + proposal.proposalId() + '|'
                + proposal.target() + '|' + artifact.strategyName() + '|'
                + artifact.artifactSha256() + '|' + release.assignmentId() + '|'
                + scopeSha + '|' + proposal.expiresAt().toEpochMilli());
        AgentEvaluationActivation activation = new AgentEvaluationActivation(
                proposal.proposalId(), proposal.target(), artifact.strategyName(),
                artifact.artifactSha256(), release.assignmentId(), scope,
                proposal.expiresAt(), useSha, false);
        AgentProposalEvaluationUseDO use = new AgentProposalEvaluationUseDO();
        use.setProposalId(activation.proposalId());
        use.setProposalUseSha256(activation.proposalUseSha256());
        use.setTargetEnvironment(activation.target().name());
        use.setStrategyName(activation.strategyName());
        use.setStrategyArtifactSha256(activation.strategyArtifactSha256());
        use.setReleaseAssignmentId(activation.releaseAssignmentId());
        use.setGovernanceSubjectSha256(release.governanceSubject().sha256());
        use.setInstrumentScopeSha256(scopeSha);
        use.setActivatedBy(required(operator, "operator"));
        use.setActivatedAtMs(nowMs);
        use.setExpiresAtMs(activation.expiresAt().toEpochMilli());
        use.setLiveExecutionAuthorized(false);
        if (mapper.insertEvaluationUse(use) != 1) {
            throw new IllegalStateException("Proposal evaluation use was not durably inserted");
        }
        afterCommit(this::refreshActiveScopes);
        return activation;
    }

    @Scheduled(fixedDelayString = "${crypto.agent.evaluation-scope-refresh-ms:1000}")
    public void refreshActiveScopes() {
        long now = clock.millis();
        List<AgentEvaluationActivation> active = new ArrayList<>();
        for (AgentProposalEvaluationUseDO use : mapper.selectActiveEvaluationUses(now)) {
            PaperShadowProposal proposal = parse(use.getProposalJson());
            active.add(new AgentEvaluationActivation(use.getProposalId(),
                    AgentProposalTarget.valueOf(use.getTargetEnvironment()),
                    use.getStrategyName(), use.getStrategyArtifactSha256(),
                    use.getReleaseAssignmentId(), normalizeScope(proposal.instrumentScope()),
                    Instant.ofEpochMilli(use.getExpiresAtMs()), use.getProposalUseSha256(), false));
        }
        active.sort(Comparator.comparing(AgentEvaluationActivation::proposalId));
        strategyManager.replaceAgentEvaluationScopes(active);
    }

    private PaperShadowProposal parse(String json) {
        try {
            return objectMapper.readValue(json, PaperShadowProposal.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Durable proposal JSON is invalid", error);
        }
    }

    private Set<String> normalizeScope(List<String> values) {
        TreeSet<String> scope = new TreeSet<>();
        for (String value : values) {
            String[] parts = required(value, "instrumentScope").split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid instrument scope: " + value);
            Exchange exchange = java.util.Arrays.stream(Exchange.values())
                    .filter(candidate -> candidate.getCode().equalsIgnoreCase(parts[0]))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown scope exchange"));
            MarketType marketType = java.util.Arrays.stream(MarketType.values())
                    .filter(candidate -> candidate.getCode().equalsIgnoreCase(parts[1]))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown scope market type"));
            scope.add(new com.tj.crypto.common.domain.InstrumentId(
                    exchange, marketType, parts[2]).value());
        }
        if (scope.isEmpty()) throw new IllegalArgumentException("instrumentScope must not be empty");
        return Set.copyOf(scope);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
