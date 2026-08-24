package com.tj.crypto.research.agent.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSandboxPolicyEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    private SandboxBudgetPolicy policy;
    private AgentSandboxPolicyEngine engine;

    @BeforeEach
    void setUp() {
        policy = new SandboxBudgetPolicy(
                SandboxBudgetPolicy.CURRENT_VERSION,
                3, 0, 2, 1, 100, 0,
                Duration.ofSeconds(1), Duration.ofMinutes(10), Duration.ofMinutes(5));
        AtomicInteger ids = new AtomicInteger();
        engine = new AgentSandboxPolicyEngine(policy, Path.of("build", "agent-sandbox"),
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "session-" + ids.incrementAndGet());
    }

    @Test
    @DisplayName("L1 和 L2 工具白名单互斥且不存在 live 工具")
    void shouldEnforceLevelSpecificToolAllowlist() {
        AgentSandboxSession l1 = engine.openSession(AgentLevel.L1, "researcher", Duration.ofMinutes(5));
        AgentSandboxSession l2 = engine.openSession(AgentLevel.L2, "proposal-agent", Duration.ofMinutes(5));

        assertThat(engine.authorize(l1.sessionId(), AgentToolName.GENERATE_EXPERIMENT_PLAN,
                SandboxAccessRequest.none()).level()).isEqualTo(AgentLevel.L1);
        assertThat(engine.authorize(l2.sessionId(), AgentToolName.SUBMIT_SHADOW_PROPOSAL,
                SandboxAccessRequest.none()).level()).isEqualTo(AgentLevel.L2);

        assertThatThrownBy(() -> engine.authorize(l1.sessionId(),
                AgentToolName.SUBMIT_PAPER_PROPOSAL, SandboxAccessRequest.none()))
                .isInstanceOf(AgentPolicyViolationException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> engine.authorize(l2.sessionId(),
                AgentToolName.GENERATE_FALSIFIABLE_HYPOTHESIS, SandboxAccessRequest.none()))
                .isInstanceOf(AgentPolicyViolationException.class)
                .hasMessageContaining("not allowed");
        assertThat(List.of(AgentToolName.values()).stream().map(Enum::name))
                .noneMatch(name -> name.contains("LIVE") || name.contains("ORDER")
                        || name.contains("SECRET") || name.contains("CONFIG")
                        || name.contains("KILL"));
        assertThat(List.of(AgentProposalTarget.values())).containsExactly(
                AgentProposalTarget.PAPER, AgentProposalTarget.SHADOW);
    }

    @Test
    @DisplayName("网络和子进程预算为零且文件只能位于会话根目录")
    void shouldFailClosedOnNetworkProcessAndPathEscape() {
        AgentSandboxSession session = engine.openSession(AgentLevel.L1, "researcher", Duration.ofMinutes(5));

        assertThatThrownBy(() -> engine.authorize(session.sessionId(),
                AgentToolName.GENERATE_EXPERIMENT_PLAN,
                new SandboxAccessRequest(1, List.of(), List.of(), 0, 0)))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("RESOURCE_BUDGET_EXCEEDED");
        assertThatThrownBy(() -> engine.authorize(session.sessionId(),
                AgentToolName.GENERATE_EXPERIMENT_PLAN,
                new SandboxAccessRequest(0, List.of(), List.of(), 0, 1)))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("RESOURCE_BUDGET_EXCEEDED");
        assertThatThrownBy(() -> engine.authorize(session.sessionId(),
                AgentToolName.GENERATE_EXPERIMENT_PLAN,
                new SandboxAccessRequest(0, List.of(Path.of("..", "secret.env")), List.of(), 1, 0)))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("SANDBOX_PATH_ESCAPE");

        AgentAuthorization allowed = engine.authorize(session.sessionId(),
                AgentToolName.GENERATE_EXPERIMENT_PLAN,
                new SandboxAccessRequest(0, List.of(Path.of("inputs", "manifest.json")),
                        List.of(Path.of("outputs", "plan.json")), 50, 0));
        assertThat(allowed.sandboxRoot()).isEqualTo(session.sandboxRoot());
    }

    @Test
    @DisplayName("累计预算、撤销和未知会话都默认拒绝")
    void shouldRevokeAndEnforceCumulativeBudget() {
        AgentSandboxSession session = engine.openSession(AgentLevel.L1, "researcher", Duration.ofMinutes(5));
        engine.authorize(session.sessionId(), AgentToolName.GENERATE_EXPERIMENT_PLAN,
                new SandboxAccessRequest(0, List.of(Path.of("a"), Path.of("b")), List.of(), 100, 0));

        assertThatThrownBy(() -> engine.authorize(session.sessionId(),
                AgentToolName.GENERATE_EXPERIMENT_PLAN,
                new SandboxAccessRequest(0, List.of(Path.of("c")), List.of(), 1, 0)))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("RESOURCE_BUDGET_EXCEEDED");

        assertThat(engine.revoke(session.sessionId(), "operator revocation").revoked()).isTrue();
        assertThatThrownBy(() -> engine.authorize(session.sessionId(),
                AgentToolName.GENERATE_EXPERIMENT_PLAN, SandboxAccessRequest.none()))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("SESSION_REVOKED");
        assertThatThrownBy(() -> engine.authorize("missing", AgentToolName.GENERATE_EXPERIMENT_PLAN,
                SandboxAccessRequest.none()))
                .isInstanceOf(AgentPolicyViolationException.class)
                .extracting("reasonCode").isEqualTo("SESSION_UNKNOWN");
    }
}
