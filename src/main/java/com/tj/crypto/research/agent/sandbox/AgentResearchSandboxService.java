package com.tj.crypto.research.agent.sandbox;

import com.tj.crypto.research.agent.policy.AgentAuthorization;
import com.tj.crypto.research.agent.policy.AgentApprovalVerificationRequest;
import com.tj.crypto.research.agent.policy.AgentApprovalVerifier;
import com.tj.crypto.research.agent.policy.AgentLevel;
import com.tj.crypto.research.agent.policy.AgentPolicyViolationException;
import com.tj.crypto.research.agent.policy.RejectingAgentApprovalVerifier;
import com.tj.crypto.research.agent.policy.AgentSandboxPolicyEngine;
import com.tj.crypto.research.agent.policy.AgentSandboxSession;
import com.tj.crypto.research.agent.policy.AgentToolName;
import com.tj.crypto.research.agent.policy.SandboxAccessRequest;
import com.tj.crypto.research.agent.policy.VerifiedAgentApproval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Agent L1/L2 的唯一研究沙盒门面。
 *
 * <p>L1 只产生可证伪假设和离线实验计划；L2 只持久化 PAPER/SHADOW 提案。
 * 本类没有交易 gateway、secret、配置发布或 Kill Switch 依赖。
 */
@Service
public class AgentResearchSandboxService {

    private final AgentSandboxPolicyEngine policyEngine;
    private final SandboxTaskRunner taskRunner;
    private final AgentToolAuditSink auditSink;
    private final AgentProposalRepository proposalRepository;
    private final AgentApprovalVerifier approvalVerifier;
    private final Clock clock;
    private final Supplier<String> ids;
    private final AgentResearchArtifactFactory artifactFactory = new AgentResearchArtifactFactory();

    @Autowired
    public AgentResearchSandboxService(
            AgentSandboxPolicyEngine policyEngine,
            SandboxTaskRunner taskRunner,
            AgentToolAuditSink auditSink,
            AgentProposalRepository proposalRepository,
            ObjectProvider<AgentApprovalVerifier> approvalVerifiers) {
        this(policyEngine, taskRunner, auditSink, proposalRepository,
                approvalVerifiers.getIfAvailable(RejectingAgentApprovalVerifier::new),
                Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    AgentResearchSandboxService(
            AgentSandboxPolicyEngine policyEngine,
            SandboxTaskRunner taskRunner,
            AgentToolAuditSink auditSink,
            AgentProposalRepository proposalRepository,
            AgentApprovalVerifier approvalVerifier,
            Clock clock,
            Supplier<String> ids) {
        if (policyEngine == null || taskRunner == null || auditSink == null
                || proposalRepository == null || approvalVerifier == null || clock == null || ids == null) {
            throw new IllegalArgumentException("All sandbox dependencies are required");
        }
        this.policyEngine = policyEngine;
        this.taskRunner = taskRunner;
        this.auditSink = auditSink;
        this.proposalRepository = proposalRepository;
        this.approvalVerifier = approvalVerifier;
        this.clock = clock;
        this.ids = ids;
    }

    @Transactional
    public AgentSandboxSession openSession(AgentLevel level, String operator, Duration ttl) {
        AgentSandboxSession session = policyEngine.openSession(level, operator, ttl);
        String callId = nextId();
        try {
            auditSink.append(event(callId, session.sessionId(), level, "OPEN_SESSION",
                    AgentAuditOutcome.SUCCEEDED, session.operator(), null, null,
                    "Session opened with a bounded, non-restorable capability"));
            return session;
        } catch (RuntimeException auditFailure) {
            policyEngine.revoke(session.sessionId(), "audit append failed");
            throw new IllegalStateException("Agent session audit failed; session was revoked", auditFailure);
        }
    }

    @Transactional
    public AgentSandboxSession revokeSession(String sessionId, String operator, String reason) {
        AgentSandboxSession revoked = policyEngine.revoke(sessionId, requireText(reason, "reason"));
        auditSink.append(event(nextId(), revoked.sessionId(), revoked.level(), "REVOKE_SESSION",
                AgentAuditOutcome.REVOKED, requireText(operator, "operator"), null, null,
                "Session capability revoked"));
        return revoked;
    }

    @Transactional
    public FalsifiableHypothesis generateHypothesis(
            String sessionId, FalsifiableHypothesisDraft draft) {
        return invoke(sessionId, AgentToolName.GENERATE_FALSIFIABLE_HYPOTHESIS,
                new AgentAuditContext(sha256(String.valueOf(draft)), null,
                        "none-deterministic", "structured-hypothesis-v1"),
                ignored -> artifactFactory.hypothesis(draft, clock.instant()),
                ignored -> { }, FalsifiableHypothesis::hypothesisId);
    }

    @Transactional
    public ExperimentPlan generateExperimentPlan(String sessionId, ExperimentPlanDraft draft) {
        return invoke(sessionId, AgentToolName.GENERATE_EXPERIMENT_PLAN,
                new AgentAuditContext(sha256(String.valueOf(draft)), null,
                        "none-deterministic", "structured-experiment-plan-v1"),
                ignored -> artifactFactory.plan(draft, clock.instant()),
                ignored -> { }, ExperimentPlan::planId);
    }

    @Transactional
    public PaperShadowProposal submitProposal(String sessionId, PaperShadowProposalRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        AgentToolName tool = switch (request.target()) {
            case PAPER -> AgentToolName.SUBMIT_PAPER_PROPOSAL;
            case SHADOW -> AgentToolName.SUBMIT_SHADOW_PROPOSAL;
        };
        return invoke(sessionId, tool,
                new AgentAuditContext(sha256(String.valueOf(request)),
                        request.approval().approvalId(), request.versions().modelVersion(),
                        request.versions().promptVersion()),
                authorization -> createProposal(authorization, request),
                proposalRepository::save,
                PaperShadowProposal::proposalId);
    }

    /** 独立控制面始终可以撤销提案；本操作只能减少权限，不能执行或晋级。 */
    @Transactional
    public void revokeProposal(String proposalId, String operator, String reason) {
        String id = requireText(proposalId, "proposalId");
        String actor = requireText(operator, "operator");
        String why = requireText(reason, "reason");
        String callId = nextId();
        auditSink.append(event(callId, "control-plane", AgentLevel.L2, "REVOKE_PROPOSAL",
                AgentAuditOutcome.STARTED, actor, null, null, "Proposal revocation started"));
        try {
            proposalRepository.revoke(id, actor, why, clock.instant());
            auditSink.append(event(callId, "control-plane", AgentLevel.L2, "REVOKE_PROPOSAL",
                    AgentAuditOutcome.REVOKED, actor, null, id, "Proposal capability revoked"));
        } catch (RuntimeException failure) {
            appendFailure(callId, "control-plane", AgentLevel.L2, "REVOKE_PROPOSAL", actor,
                    "PROPOSAL_REVOKE_FAILED", failure);
            throw failure;
        }
    }

    private PaperShadowProposal createProposal(AgentAuthorization authorization,
                                               PaperShadowProposalRequest request) {
        Instant now = clock.instant();
        if (request.ttl().compareTo(policyEngine.policy().maxProposalTtl()) > 0) {
            throw new AgentPolicyViolationException(
                    "PROPOSAL_TTL_EXCEEDED", "Proposal TTL exceeds the short-lived policy maximum");
        }
        Instant expiresAt = now.plus(request.ttl());
        if (expiresAt.isAfter(authorization.sessionExpiresAt())) {
            throw new AgentPolicyViolationException(
                    "PROPOSAL_EXCEEDS_SESSION", "Proposal cannot outlive its sandbox session");
        }
        request.versions().requirePolicyVersion(policyEngine.policy().policyVersion());
        String requestingOperator = authorization.operator();
        String approvalPayloadSha256 = request.approvalPayloadSha256(requestingOperator);
        request.approval().validateFor(
                requestingOperator,
                request.target(),
                approvalPayloadSha256,
                now,
                expiresAt);
        String proposalId = artifactFactory.proposalId(request, requestingOperator);
        String approvalUseId = approvalUseId(proposalId, requestingOperator);
        AgentApprovalVerificationRequest verificationRequest = new AgentApprovalVerificationRequest(
                approvalUseId,
                AgentApprovalVerifier.PAPER_SHADOW_AUDIENCE,
                request.approval(),
                requestingOperator,
                request.target(),
                approvalPayloadSha256,
                now,
                expiresAt);
        VerifiedAgentApproval verified = approvalVerifier.verifyAndConsume(verificationRequest);
        if (verified == null) {
            throw new AgentPolicyViolationException(
                    "APPROVAL_VERIFICATION_FAILED", "Trusted approval verifier returned no receipt");
        }
        verified.requireExactMatch(verificationRequest);
        return new PaperShadowProposal(
                proposalId,
                authorization.sessionId(),
                request.target(),
                request.hypothesisId(),
                request.experimentPlanId(),
                request.strategyArtifactId(),
                request.instrumentScope(),
                request.objective(),
                request.approval(),
                verified.verificationReceiptSha256(),
                request.versions(),
                now,
                expiresAt,
                PaperShadowProposal.ProposalStatus.SUBMITTED);
    }

    private <T> T invoke(
            String sessionId,
            AgentToolName tool,
            AgentAuditContext auditContext,
            Function<AgentAuthorization, T> task,
            Consumer<T> persist,
            Function<T, String> resultReference) {
        String callId = nextId();
        AgentAuthorization authorization;
        try {
            authorization = policyEngine.authorize(sessionId, tool, SandboxAccessRequest.none());
        } catch (RuntimeException denied) {
            String reasonCode = denied instanceof AgentPolicyViolationException violation
                    ? violation.reasonCode() : "AUTHORIZATION_FAILED";
            auditSink.append(event(callId, safeSessionId(sessionId), requiredLevel(tool), tool.name(),
                    AgentAuditOutcome.DENIED, "unknown", reasonCode, null,
                    "Deterministic policy denied the invocation", auditContext));
            throw denied;
        }
        long startedAtNanos = System.nanoTime();
        try {
            auditSink.append(event(callId, authorization.sessionId(), authorization.level(), tool.name(),
                    AgentAuditOutcome.STARTED, authorization.operator(), null, null,
                    "Invocation authorized by explicit allowlist", auditContext));
        } catch (RuntimeException auditFailure) {
            policyEngine.revoke(authorization.sessionId(), "audit start append failed");
            throw new IllegalStateException("Agent audit failed before tool execution; session revoked", auditFailure);
        }

        try {
            Duration remainingSession = Duration.between(clock.instant(), authorization.sessionExpiresAt());
            if (remainingSession.isZero() || remainingSession.isNegative()) {
                throw new AgentPolicyViolationException("SESSION_EXPIRED", "Sandbox session expired before execution");
            }
            Duration invocationTimeout = remainingSession.compareTo(policyEngine.policy().maxInvocationTime()) < 0
                    ? remainingSession : policyEngine.policy().maxInvocationTime();
            T result = taskRunner.run(invocationTimeout,
                    () -> task.apply(authorization));
            persist.accept(result);
            long elapsedMs = elapsedMs(startedAtNanos);
            try {
                auditSink.append(event(callId, authorization.sessionId(), authorization.level(), tool.name(),
                        AgentAuditOutcome.SUCCEEDED, authorization.operator(), null,
                        resultReference.apply(result), "Invocation completed within resource budgets",
                        elapsedMs, auditContext));
            } catch (RuntimeException auditFailure) {
                policyEngine.revoke(authorization.sessionId(), "terminal audit append failed");
                throw new IllegalStateException("Agent terminal audit failed; session revoked", auditFailure);
            }
            return result;
        } catch (SandboxTimeoutException timeout) {
            policyEngine.revoke(authorization.sessionId(), "tool timeout");
            appendTerminalFailure(callId, authorization, tool, AgentAuditOutcome.TIMED_OUT,
                    "INVOCATION_TIMEOUT", timeout, startedAtNanos, auditContext);
            throw timeout;
        } catch (RuntimeException failure) {
            policyEngine.revoke(authorization.sessionId(), "tool or persistence failure");
            appendTerminalFailure(callId, authorization, tool, AgentAuditOutcome.FAILED,
                    failure instanceof AgentPolicyViolationException violation
                            ? violation.reasonCode() : "INVOCATION_FAILED",
                    failure, startedAtNanos, auditContext);
            throw failure;
        }
    }

    private void appendTerminalFailure(String callId, AgentAuthorization authorization,
                                       AgentToolName tool, AgentAuditOutcome outcome,
                                       String reasonCode, RuntimeException original,
                                       long startedAtNanos, AgentAuditContext auditContext) {
        try {
            auditSink.append(event(callId, authorization.sessionId(), authorization.level(), tool.name(),
                    outcome, authorization.operator(), reasonCode, null,
                    "Invocation failed closed", elapsedMs(startedAtNanos), auditContext));
        } catch (RuntimeException auditFailure) {
            original.addSuppressed(auditFailure);
        }
    }

    private void appendFailure(String callId, String sessionId, AgentLevel level, String operation,
                               String operator, String reasonCode, RuntimeException original) {
        try {
            auditSink.append(event(callId, sessionId, level, operation, AgentAuditOutcome.FAILED,
                    operator, reasonCode, null, "Control operation failed closed"));
        } catch (RuntimeException auditFailure) {
            original.addSuppressed(auditFailure);
        }
    }

    private AgentToolAuditEvent event(String callId, String sessionId, AgentLevel level,
                                      String operation, AgentAuditOutcome outcome, String operator,
                                      String reasonCode, String resultReference, String detail) {
        return event(callId, sessionId, level, operation, outcome, operator, reasonCode,
                resultReference, detail, null, AgentAuditContext.empty());
    }

    private AgentToolAuditEvent event(String callId, String sessionId, AgentLevel level,
                                      String operation, AgentAuditOutcome outcome, String operator,
                                      String reasonCode, String resultReference, String detail,
                                      AgentAuditContext auditContext) {
        return event(callId, sessionId, level, operation, outcome, operator, reasonCode,
                resultReference, detail, null, auditContext);
    }

    private AgentToolAuditEvent event(String callId, String sessionId, AgentLevel level,
                                      String operation, AgentAuditOutcome outcome, String operator,
                                      String reasonCode, String resultReference, String detail,
                                      Long elapsedMs, AgentAuditContext auditContext) {
        return new AgentToolAuditEvent(nextId(), callId, sessionId, level, operation, outcome,
                operator, policyEngine.policy().policyVersion(), auditContext.inputSha256(),
                auditContext.approvalReference(), auditContext.modelVersion(), auditContext.promptVersion(),
                clock.instant(), elapsedMs,
                reasonCode, resultReference, detail);
    }

    private AgentLevel requiredLevel(AgentToolName tool) {
        return switch (tool) {
            case GENERATE_FALSIFIABLE_HYPOTHESIS, GENERATE_EXPERIMENT_PLAN -> AgentLevel.L1;
            case SUBMIT_PAPER_PROPOSAL, SUBMIT_SHADOW_PROPOSAL -> AgentLevel.L2;
        };
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private String nextId() {
        return requireText(ids.get(), "generated id");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 一次性消费键独立、显式地内容寻址绑定 proposal 与已认证 operator。 */
    private String approvalUseId(String proposalId, String requestingOperator) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFramed(digest, "agent-paper-shadow-approval-use-v2");
            updateFramed(digest, requireText(proposalId, "proposalId"));
            updateFramed(digest, requireText(requestingOperator, "requestingOperator"));
            return "approval-use-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void updateFramed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String safeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "missing" : sessionId;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
