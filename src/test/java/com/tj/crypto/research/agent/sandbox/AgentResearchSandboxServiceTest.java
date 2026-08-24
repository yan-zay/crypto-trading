package com.tj.crypto.research.agent.sandbox;

import com.tj.crypto.research.agent.policy.AgentApprovalReference;
import com.tj.crypto.research.agent.policy.AgentApprovalVerificationRequest;
import com.tj.crypto.research.agent.policy.AgentApprovalVerifier;
import com.tj.crypto.research.agent.policy.AgentArtifactVersions;
import com.tj.crypto.research.agent.policy.AgentLevel;
import com.tj.crypto.research.agent.policy.AgentPolicyViolationException;
import com.tj.crypto.research.agent.policy.AgentProposalTarget;
import com.tj.crypto.research.agent.policy.AgentSandboxPolicyEngine;
import com.tj.crypto.research.agent.policy.AgentSandboxSession;
import com.tj.crypto.research.agent.policy.AgentToolName;
import com.tj.crypto.research.agent.policy.SandboxAccessRequest;
import com.tj.crypto.research.agent.policy.SandboxBudgetPolicy;
import com.tj.crypto.research.agent.policy.RejectingAgentApprovalVerifier;
import com.tj.crypto.research.agent.policy.VerifiedAgentApproval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentResearchSandboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SandboxBudgetPolicy budget;
    private AgentSandboxPolicyEngine policyEngine;
    private CollectingAuditSink audit;
    private InMemoryProposalRepository proposals;
    private FakeTrustedApprovalVerifier approvals;
    private AtomicInteger approvalIds;
    private AgentResearchSandboxService service;

    @BeforeEach
    void setUp() {
        budget = SandboxBudgetPolicy.restrictiveDefault();
        AtomicInteger sessions = new AtomicInteger();
        policyEngine = new AgentSandboxPolicyEngine(budget, Path.of("build", "agent-sandbox"), CLOCK,
                () -> "session-" + sessions.incrementAndGet());
        audit = new CollectingAuditSink();
        proposals = new InMemoryProposalRepository();
        approvals = new FakeTrustedApprovalVerifier();
        approvalIds = new AtomicInteger();
        service = new AgentResearchSandboxService(policyEngine, directRunner(), audit, proposals,
                approvals, CLOCK, sequence("audit"));
    }

    @Test
    @DisplayName("L1 只生成结构化可证伪假设和可复现实验计划")
    void shouldCreateOnlyStructuredL1Artifacts() {
        AgentSandboxSession session = service.openSession(AgentLevel.L1, "researcher-a", Duration.ofMinutes(20));

        FalsifiableHypothesis hypothesis = service.generateHypothesis(session.sessionId(), hypothesisDraft());
        ExperimentPlan plan = service.generateExperimentPlan(session.sessionId(), planDraft(hypothesis.hypothesisId()));

        assertThat(hypothesis.hypothesisId()).startsWith("hyp-");
        assertThat(hypothesis.nullHypothesis()).isNotBlank();
        assertThat(hypothesis.rejectionRule()).isNotBlank();
        assertThat(hypothesis.failedResultMustBeRegistered()).isTrue();
        assertThat(plan.planId()).startsWith("plan-");
        assertThat(plan.datasetSha256()).hasSize(64);
        assertThat(plan.randomSeed()).isEqualTo(42L);
        assertThat(plan.failedResultMustBeRegistered()).isTrue();
        assertThat(proposals.saved).isEmpty();
        assertThat(audit.events.stream().filter(event -> event.outcome() == AgentAuditOutcome.STARTED))
                .hasSize(2);
        assertThat(audit.events.stream().filter(event -> event.outcome() == AgentAuditOutcome.SUCCEEDED))
                .hasSize(3); // open session + two tools
    }

    @Test
    @DisplayName("L2 只持久化带短 TTL、独立审批和完整版本的 PAPER/SHADOW 提案")
    void shouldSubmitBoundedPaperAndShadowProposals() {
        AgentSandboxSession session = service.openSession(AgentLevel.L2, "agent-l2", Duration.ofMinutes(20));
        PaperShadowProposalRequest paperRequest = proposalRequest(AgentProposalTarget.PAPER,
                "human-reviewer", "agent-l2", Duration.ofMinutes(5), versions(budget.policyVersion()));

        PaperShadowProposal proposal = service.submitProposal(session.sessionId(), paperRequest);

        assertThat(proposal.target()).isEqualTo(AgentProposalTarget.PAPER);
        assertThat(proposal.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(proposal.status()).isEqualTo(PaperShadowProposal.ProposalStatus.SUBMITTED);
        assertThat(proposal.approval().approvedPayloadSha256())
                .isEqualTo(paperRequest.approvalPayloadSha256("agent-l2"));
        assertThat(proposal.approvalVerificationReceiptSha256()).matches("[0-9a-f]{64}");
        assertThat(proposal.versions().gitCommit()).hasSize(40);
        assertThat(proposal.versions().containerImageDigest()).startsWith("sha256:");
        assertThat(proposals.saved).containsExactly(proposal);
        assertThat(audit.events).anyMatch(event -> event.operation().equals("SUBMIT_PAPER_PROPOSAL")
                && event.outcome() == AgentAuditOutcome.SUCCEEDED
                && proposal.proposalId().equals(event.resultReference()));

        AgentSandboxSession shadowSession = service.openSession(
                AgentLevel.L2, "agent-shadow", Duration.ofMinutes(20));
        PaperShadowProposal shadow = service.submitProposal(shadowSession.sessionId(),
                proposalRequest(AgentProposalTarget.SHADOW, "reviewer-b", "agent-shadow", Duration.ofMinutes(3),
                        versions(budget.policyVersion())));
        assertThat(shadow.target()).isEqualTo(AgentProposalTarget.SHADOW);
        assertThat(Arrays.stream(AgentProposalTarget.values()).map(Enum::name))
                .noneMatch("LIVE"::equals);
    }

    @Test
    @DisplayName("未配置可信审批 verifier 时不能用自报字段放行 L2")
    void shouldRejectSelfReportedApprovalWhenVerifierIsUnavailable() {
        AgentResearchSandboxService unverifiedService = new AgentResearchSandboxService(
                policyEngine, directRunner(), audit, proposals, new RejectingAgentApprovalVerifier(),
                CLOCK, sequence("unverified"));
        AgentSandboxSession session = unverifiedService.openSession(
                AgentLevel.L2, "agent-unverified", Duration.ofMinutes(20));
        PaperShadowProposalRequest request = proposalRequest(
                AgentProposalTarget.PAPER, "independent-reviewer", "agent-unverified", Duration.ofMinutes(5),
                versions(budget.policyVersion()));

        assertThatThrownBy(() -> unverifiedService.submitProposal(session.sessionId(), request))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("APPROVAL_VERIFIER_UNAVAILABLE");
        assertThat(proposals.saved).isEmpty();
        assertThatThrownBy(() -> policyEngine.authorize(session.sessionId(),
                AgentToolName.SUBMIT_PAPER_PROPOSAL, SandboxAccessRequest.none()))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("SESSION_REVOKED");
    }

    @Test
    @DisplayName("可信审批必须验证身份且只能为一个精确 useId 消费一次")
    void shouldRejectForgedApprovalIdentityAndReplay() {
        AgentSandboxSession firstSession = service.openSession(
                AgentLevel.L2, "agent-first", Duration.ofMinutes(20));
        PaperShadowProposalRequest trusted = proposalRequest(
                AgentProposalTarget.SHADOW, "trusted-reviewer", "agent-first", Duration.ofMinutes(5),
                versions(budget.policyVersion()));
        PaperShadowProposal first = service.submitProposal(firstSession.sessionId(), trusted);

        AgentSandboxSession replaySession = service.openSession(
                AgentLevel.L2, "agent-first", Duration.ofMinutes(20));
        assertThatThrownBy(() -> service.submitProposal(replaySession.sessionId(), trusted))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("APPROVAL_ALREADY_CONSUMED");

        AgentSandboxSession forgedSession = service.openSession(
                AgentLevel.L2, "agent-forged", Duration.ofMinutes(20));
        PaperShadowProposalRequest original = proposalRequest(
                AgentProposalTarget.PAPER, "real-reviewer", "agent-forged", Duration.ofMinutes(5),
                versions(budget.policyVersion()));
        AgentApprovalReference forgedReference = new AgentApprovalReference(
                original.approval().approvalId(),
                original.approval().approvalArtifactSha256(),
                original.approval().approvedPayloadSha256(),
                "forged-reviewer",
                original.approval().approvedAt(),
                original.approval().expiresAt(),
                original.approval().approvedTarget());
        PaperShadowProposalRequest forged = new PaperShadowProposalRequest(
                original.target(), original.hypothesisId(), original.experimentPlanId(),
                original.strategyArtifactId(), original.instrumentScope(), original.objective(),
                original.ttl(), forgedReference, original.versions());

        assertThatThrownBy(() -> service.submitProposal(forgedSession.sessionId(), forged))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("APPROVAL_SIGNATURE_OR_IDENTITY_INVALID");
        assertThat(proposals.saved).containsExactly(first);
    }

    @Test
    @DisplayName("签名审批只能由其绑定的 L2 operator 消费，抢先提交不会消耗审批")
    void shouldBindSignedApprovalToRequestingOperatorBeforeConsumption() {
        PaperShadowProposalRequest signedForOwner = proposalRequest(
                AgentProposalTarget.PAPER, "independent-reviewer", "agent-owner",
                Duration.ofMinutes(5), versions(budget.policyVersion()));
        AgentSandboxSession attacker = service.openSession(
                AgentLevel.L2, "agent-attacker", Duration.ofMinutes(20));

        assertThatThrownBy(() -> service.submitProposal(attacker.sessionId(), signedForOwner))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("APPROVAL_PAYLOAD_MISMATCH");
        assertThat(approvals.verificationAttempts).isZero();
        assertThat(approvals.consumedApprovals).isEmpty();
        assertThat(proposals.saved).isEmpty();

        AgentSandboxSession owner = service.openSession(
                AgentLevel.L2, "agent-owner", Duration.ofMinutes(20));
        PaperShadowProposal accepted = service.submitProposal(owner.sessionId(), signedForOwner);

        assertThat(approvals.verificationAttempts).isEqualTo(1);
        assertThat(approvals.consumedApprovals)
                .containsExactly(signedForOwner.approval().approvalId());
        assertThat(proposals.saved).containsExactly(accepted);
        assertThatThrownBy(signedForOwner::approvalPayloadSha256)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requestingOperator is required");

        String legacyUseId = "approval-use-" + "a".repeat(64);
        AgentApprovalVerificationRequest exactRequest = new AgentApprovalVerificationRequest(
                legacyUseId, AgentApprovalVerifier.PAPER_SHADOW_AUDIENCE,
                signedForOwner.approval(), "agent-owner", signedForOwner.target(),
                signedForOwner.approvalPayloadSha256("agent-owner"), NOW,
                NOW.plus(signedForOwner.ttl()));
        VerifiedAgentApproval legacyUnboundReceipt = new VerifiedAgentApproval(
                signedForOwner.approval().approvalId(),
                signedForOwner.approval().approvalArtifactSha256(),
                signedForOwner.approval().approvedPayloadSha256(),
                signedForOwner.approval().approvedBy(),
                AgentApprovalVerifier.PAPER_SHADOW_AUDIENCE,
                signedForOwner.target(), legacyUseId, signedForOwner.approval().expiresAt(),
                digest("legacy-unbound-receipt"));
        assertThatThrownBy(() -> legacyUnboundReceipt.requireExactMatch(exactRequest))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("APPROVAL_OPERATOR_MISMATCH");
    }

    @Test
    @DisplayName("审批不能复用到被篡改负载、错误环境、自批或过期窗口")
    void shouldRejectInvalidApprovalReferences() {
        AgentSandboxSession payloadSession = service.openSession(
                AgentLevel.L2, "agent-payload", Duration.ofMinutes(20));
        PaperShadowProposalRequest original = proposalRequest(AgentProposalTarget.PAPER,
                "reviewer", "agent-payload", Duration.ofMinutes(5), versions(budget.policyVersion()));
        AgentApprovalReference wrongPayload = new AgentApprovalReference(
                original.approval().approvalId(), original.approval().approvalArtifactSha256(),
                "f".repeat(64), "reviewer", NOW.minusSeconds(60), NOW.plusSeconds(1200),
                AgentProposalTarget.PAPER);
        PaperShadowProposalRequest tampered = new PaperShadowProposalRequest(
                original.target(), original.hypothesisId(), original.experimentPlanId(),
                original.strategyArtifactId(), original.instrumentScope(), "tampered objective",
                original.ttl(), wrongPayload, original.versions());

        assertThatThrownBy(() -> service.submitProposal(payloadSession.sessionId(), tampered))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("APPROVAL_PAYLOAD_MISMATCH");

        AgentSandboxSession selfSession = service.openSession(
                AgentLevel.L2, "self-agent", Duration.ofMinutes(20));
        PaperShadowProposalRequest selfApproved = proposalRequest(AgentProposalTarget.SHADOW,
                "self-agent", "self-agent", Duration.ofMinutes(5), versions(budget.policyVersion()));
        assertThatThrownBy(() -> service.submitProposal(selfSession.sessionId(), selfApproved))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("SELF_APPROVAL_FORBIDDEN");

        AgentSandboxSession ttlSession = service.openSession(
                AgentLevel.L2, "ttl-agent", Duration.ofMinutes(20));
        PaperShadowProposalRequest tooLong = proposalRequest(AgentProposalTarget.PAPER,
                "reviewer", "ttl-agent", Duration.ofMinutes(11), versions(budget.policyVersion()));
        assertThatThrownBy(() -> service.submitProposal(ttlSession.sessionId(), tooLong))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("PROPOSAL_TTL_EXCEEDED");

        assertThat(proposals.saved).isEmpty();
        assertThat(audit.events).anyMatch(event -> event.outcome() == AgentAuditOutcome.FAILED);
    }

    @Test
    @DisplayName("跨级调用、审计故障和工具超时都 fail-closed 并撤销会话")
    void shouldFailClosedAndRevokeCapabilities() {
        AgentSandboxSession l1 = service.openSession(AgentLevel.L1, "researcher", Duration.ofMinutes(10));
        assertThatThrownBy(() -> policyEngine.authorize(l1.sessionId(),
                AgentToolName.SUBMIT_PAPER_PROPOSAL, SandboxAccessRequest.none()))
                .isInstanceOf(AgentPolicyViolationException.class);

        CollectingAuditSink failingAudit = new CollectingAuditSink();
        failingAudit.failOnStarted = true;
        AgentResearchSandboxService auditFailureService = new AgentResearchSandboxService(
                policyEngine, directRunner(), failingAudit, proposals, approvals,
                CLOCK, sequence("fail-audit"));
        assertThatThrownBy(() -> auditFailureService.generateHypothesis(l1.sessionId(), hypothesisDraft()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit failed before");
        assertThatThrownBy(() -> policyEngine.authorize(l1.sessionId(),
                AgentToolName.GENERATE_EXPERIMENT_PLAN, SandboxAccessRequest.none()))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("SESSION_REVOKED");

        AgentSandboxSession timeoutSession = service.openSession(
                AgentLevel.L1, "slow-researcher", Duration.ofMinutes(10));
        SandboxTaskRunner timeoutRunner = new SandboxTaskRunner() {
            @Override
            public <T> T run(Duration timeout, Callable<T> task) {
                throw new SandboxTimeoutException("simulated timeout", null);
            }
        };
        AgentResearchSandboxService timeoutService = new AgentResearchSandboxService(
                policyEngine, timeoutRunner, audit, proposals, approvals,
                CLOCK, sequence("timeout"));
        assertThatThrownBy(() -> timeoutService.generateHypothesis(timeoutSession.sessionId(), hypothesisDraft()))
                .isInstanceOf(SandboxTimeoutException.class);
        assertThat(audit.events).anyMatch(event -> event.outcome() == AgentAuditOutcome.TIMED_OUT);
        assertThatThrownBy(() -> policyEngine.authorize(timeoutSession.sessionId(),
                AgentToolName.GENERATE_EXPERIMENT_PLAN, SandboxAccessRequest.none()))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("SESSION_REVOKED");
    }

    @Test
    @DisplayName("撤销只能降低能力且每次工具调用都有追加式审计")
    void shouldRevokeProposalAndAuditEveryInvocation() {
        AgentSandboxSession session = service.openSession(AgentLevel.L2, "agent-l2", Duration.ofMinutes(20));
        PaperShadowProposal proposal = service.submitProposal(session.sessionId(),
                proposalRequest(AgentProposalTarget.PAPER, "reviewer", "agent-l2", Duration.ofMinutes(5),
                        versions(budget.policyVersion())));

        service.revokeProposal(proposal.proposalId(), "risk-controller", "evidence expired");
        AgentSandboxSession revoked = service.revokeSession(
                session.sessionId(), "risk-controller", "operator revocation");

        assertThat(revoked.revoked()).isTrue();
        assertThat(proposals.revoked).containsExactly(proposal.proposalId());
        assertThat(audit.events).anyMatch(event -> event.operation().equals("REVOKE_PROPOSAL")
                && event.outcome() == AgentAuditOutcome.REVOKED);
        assertThat(audit.events).anyMatch(event -> event.operation().equals("REVOKE_SESSION")
                && event.outcome() == AgentAuditOutcome.REVOKED);
        assertThat(audit.events.stream().map(AgentToolAuditEvent::callId).distinct().count())
                .isEqualTo(4); // open, submit, revoke proposal, revoke session
    }

    @Test
    @DisplayName("Agent 沙盒没有交易、密钥、配置发布或 Kill Switch 依赖")
    void shouldHaveNoForbiddenMoneyPlaneDependencies() {
        List<Class<?>> boundaryTypes = List.of(
                AgentResearchSandboxService.class,
                AgentSandboxPolicyEngine.class,
                PersistentAgentProposalRepository.class,
                PersistentAgentToolAuditSink.class,
                AgentProposalRepository.class,
                AgentToolAuditSink.class);

        assertThat(boundaryTypes.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType)
                .map(Class::getName))
                .noneMatch(type -> type.contains(".execution.")
                        || type.contains(".trading.venue.")
                        || type.contains("KillSwitch")
                        || type.contains("ConfigVersion")
                        || type.toLowerCase().contains("credential")
                        || type.toLowerCase().contains("secret"));
    }

    private FalsifiableHypothesisDraft hypothesisDraft() {
        return new FalsifiableHypothesisDraft(
                "Funding mean reversion",
                "Extreme funding predicts lower next-day excess return after costs",
                "Funding has no point-in-time predictive information after costs",
                "BINANCE PERPETUAL BTCUSDT 1h",
                List.of("funding_zscore"), List.of("next_day_excess_return"), "negative",
                "Deflated Sharpe", "DSR probability >= 0.95", "Reject if OOS DSR < 0.95",
                "always-flat and buy-and-hold", List.of("market beta", "volatility regime"),
                List.of("point-in-time join", "purged folds", "embargo"), List.of("dataset-v1"));
    }

    private ExperimentPlanDraft planDraft(String hypothesisId) {
        return new ExperimentPlanDraft(
                hypothesisId, "dataset-v1", "a".repeat(64), "git:abc", "config-v1",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-02-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                Duration.ofDays(2), Duration.ofDays(2), 42L,
                List.of("always-flat", "buy-and-hold"), List.of("DSR", "PBO", "capacity"),
                List.of("OOS DSR >= 0.95"), List.of("PBO > 0.2"), Duration.ofHours(1));
    }

    private PaperShadowProposalRequest proposalRequest(AgentProposalTarget target, String approvedBy,
                                                       String requestingOperator, Duration ttl,
                                                       AgentArtifactVersions versions) {
        String approvalId = "approval-" + approvalIds.incrementAndGet();
        String approvalArtifactSha256 = digest("signed-artifact:" + approvalId);
        AgentApprovalReference placeholder = new AgentApprovalReference(
                approvalId, approvalArtifactSha256, "0".repeat(64), approvedBy,
                NOW.minusSeconds(60), NOW.plus(Duration.ofMinutes(30)), target);
        PaperShadowProposalRequest unsigned = new PaperShadowProposalRequest(
                target, "hyp-1", "plan-1", "strategy-artifact-1",
                List.of("BINANCE:PERPETUAL:BTCUSDT"), "forward parity evaluation",
                ttl, placeholder, versions);
        AgentApprovalReference signed = new AgentApprovalReference(
                approvalId, approvalArtifactSha256,
                unsigned.approvalPayloadSha256(requestingOperator), approvedBy,
                NOW.minusSeconds(60), NOW.plus(Duration.ofMinutes(30)), target);
        approvals.trust(signed, requestingOperator);
        return new PaperShadowProposalRequest(
                target, unsigned.hypothesisId(), unsigned.experimentPlanId(),
                unsigned.strategyArtifactId(), unsigned.instrumentScope(), unsigned.objective(),
                ttl, signed, versions);
    }

    private AgentArtifactVersions versions(String policyVersion) {
        return new AgentArtifactVersions(
                "a".repeat(40), "b".repeat(64), "sha256:" + "c".repeat(64),
                "dataset-v1", "d".repeat(64), "config-v1", "strategy-v1",
                "agent-runtime-v1", "none-deterministic", "structured-input-v1",
                policyVersion, 42L);
    }

    private SandboxTaskRunner directRunner() {
        return new SandboxTaskRunner() {
            @Override
            public <T> T run(Duration timeout, Callable<T> task) {
                try {
                    return task.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private java.util.function.Supplier<String> sequence(String prefix) {
        AtomicInteger ids = new AtomicInteger();
        return () -> prefix + "-" + ids.incrementAndGet();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CollectingAuditSink implements AgentToolAuditSink {
        private final List<AgentToolAuditEvent> events = new ArrayList<>();
        private boolean failOnStarted;

        @Override
        public void append(AgentToolAuditEvent event) {
            if (failOnStarted && event.outcome() == AgentAuditOutcome.STARTED) {
                throw new IllegalStateException("audit unavailable");
            }
            events.add(event);
        }
    }

    private static final class InMemoryProposalRepository implements AgentProposalRepository {
        private final List<PaperShadowProposal> saved = new ArrayList<>();
        private final List<String> revoked = new ArrayList<>();

        @Override
        public void save(PaperShadowProposal proposal) {
            saved.add(proposal);
        }

        @Override
        public void revoke(String proposalId, String operator, String reason, Instant revokedAt) {
            if (saved.stream().noneMatch(proposal -> proposal.proposalId().equals(proposalId))) {
                throw new IllegalStateException("missing proposal");
            }
            revoked.add(proposalId);
        }
    }

    /** 测试专用可信边界：先登记签名制品，再按 approvalId 原子消费一次。 */
    private static final class FakeTrustedApprovalVerifier implements AgentApprovalVerifier {
        private final Map<String, TrustedApproval> trustedArtifacts = new HashMap<>();
        private final Set<String> consumedApprovals = new HashSet<>();
        private int verificationAttempts;

        synchronized void trust(AgentApprovalReference reference, String requestingOperator) {
            TrustedApproval candidate = new TrustedApproval(reference, requestingOperator);
            TrustedApproval existing = trustedArtifacts.putIfAbsent(reference.approvalId(), candidate);
            if (existing != null && !existing.equals(candidate)) {
                throw new IllegalArgumentException("conflicting trusted test approval");
            }
        }

        @Override
        public synchronized VerifiedAgentApproval verifyAndConsume(AgentApprovalVerificationRequest request) {
            verificationAttempts++;
            if (!PAPER_SHADOW_AUDIENCE.equals(request.audience())) {
                throw new AgentPolicyViolationException(
                        "APPROVAL_AUDIENCE_MISMATCH", "unexpected approval audience");
            }
            TrustedApproval artifact = trustedArtifacts.get(
                    request.claimedReference().approvalId());
            if (artifact == null || !artifact.reference().equals(request.claimedReference())) {
                throw new AgentPolicyViolationException(
                        "APPROVAL_SIGNATURE_OR_IDENTITY_INVALID",
                        "test trust store did not authenticate the exact signed approval artifact");
            }
            AgentApprovalReference trusted = artifact.reference();
            if (!trusted.approvedPayloadSha256().equals(request.requestPayloadSha256())
                    || trusted.approvedTarget() != request.target()) {
                throw new AgentPolicyViolationException(
                        "APPROVAL_PAYLOAD_MISMATCH", "signed approval request boundary mismatch");
            }
            if (!artifact.requestingOperator().equals(request.requestingOperator())) {
                throw new AgentPolicyViolationException(
                        "APPROVAL_OPERATOR_MISMATCH", "signed approval operator mismatch");
            }
            if (!consumedApprovals.add(trusted.approvalId())) {
                throw new AgentPolicyViolationException(
                        "APPROVAL_ALREADY_CONSUMED", "signed approval is single use");
            }
            return new VerifiedAgentApproval(
                    trusted.approvalId(),
                    trusted.approvalArtifactSha256(),
                    trusted.approvedPayloadSha256(),
                    trusted.approvedBy(),
                    request.audience(),
                    artifact.requestingOperator(),
                    trusted.approvedTarget(),
                    request.useId(),
                    trusted.expiresAt(),
                    digest("verified:" + trusted.approvalId() + ":"
                            + artifact.requestingOperator() + ":" + request.useId()));
        }

        private record TrustedApproval(
                AgentApprovalReference reference,
                String requestingOperator) {
        }
    }
}
