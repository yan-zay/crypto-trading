package com.tj.crypto.research.agent.policy;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * L1/L2 的进程内能力令牌与累计预算执行器。
 *
 * <p>会话不会从数据库恢复：进程重启会让所有能力令牌立即失效，这是刻意的 fail-closed 语义。
 */
@Component
public class AgentSandboxPolicyEngine {

    private static final Map<AgentLevel, Set<AgentToolName>> TOOL_ALLOWLIST = allowlist();

    private final SandboxBudgetPolicy policy;
    private final Path sandboxBase;
    private final Clock clock;
    private final Supplier<String> sessionIds;
    private final Map<String, MutableSession> sessions = new ConcurrentHashMap<>();

    public AgentSandboxPolicyEngine() {
        this(SandboxBudgetPolicy.restrictiveDefault(), Path.of("var", "agent-sandbox"),
                Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    public AgentSandboxPolicyEngine(SandboxBudgetPolicy policy, Path sandboxBase, Clock clock,
                                    Supplier<String> sessionIds) {
        if (policy == null || sandboxBase == null || clock == null || sessionIds == null) {
            throw new IllegalArgumentException("Policy, sandboxBase, clock and sessionIds are required");
        }
        this.policy = policy;
        this.sandboxBase = sandboxBase.toAbsolutePath().normalize();
        this.clock = clock;
        this.sessionIds = sessionIds;
    }

    public AgentSandboxSession openSession(AgentLevel level, String operator, Duration ttl) {
        if (level == null) throw violation("LEVEL_REQUIRED", "Agent level is required");
        String normalizedOperator = requireText(operator, "operator");
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(policy.maxSessionTtl()) > 0) {
            throw violation("SESSION_TTL_INVALID", "Session TTL must be positive and within policy maximum");
        }
        Instant now = clock.instant();
        String sessionId = requireText(sessionIds.get(), "sessionId");
        Path root = sandboxBase.resolve(sessionId).normalize();
        if (!root.startsWith(sandboxBase)) {
            throw violation("SANDBOX_ROOT_ESCAPE", "Generated sandbox root escaped base directory");
        }
        MutableSession state = new MutableSession(sessionId, level, normalizedOperator, root,
                now, now.plus(ttl));
        if (sessions.putIfAbsent(sessionId, state) != null) {
            throw violation("SESSION_ID_COLLISION", "Sandbox session identifier already exists");
        }
        return state.snapshot();
    }

    public AgentAuthorization authorize(String sessionId, AgentToolName tool, SandboxAccessRequest access) {
        String id = requireText(sessionId, "sessionId");
        if (tool == null) throw violation("TOOL_REQUIRED", "Agent tool is required");
        if (access == null) throw violation("ACCESS_DECLARATION_REQUIRED", "Sandbox access declaration is required");
        MutableSession session = sessions.get(id);
        if (session == null) throw violation("SESSION_UNKNOWN", "Sandbox session is unknown or was invalidated");
        synchronized (session) {
            Instant now = clock.instant();
            if (session.revoked) throw violation("SESSION_REVOKED", "Sandbox session is revoked");
            if (!now.isBefore(session.expiresAt)) {
                session.revoked = true;
                throw violation("SESSION_EXPIRED", "Sandbox session has expired");
            }
            Set<AgentToolName> allowed = TOOL_ALLOWLIST.getOrDefault(session.level, Set.of());
            if (!allowed.contains(tool)) {
                throw violation("TOOL_NOT_ALLOWED", "Tool is not allowed for " + session.level + ": " + tool);
            }
            validatePaths(session.root, access);
            int nextToolCalls = Math.addExact(session.toolCalls, 1);
            int nextNetworkCalls = Math.addExact(session.networkCalls, access.networkCalls());
            int nextFileReads = Math.addExact(session.fileReads, access.fileReads().size());
            int nextFileWrites = Math.addExact(session.fileWrites, access.fileWrites().size());
            long nextFileBytes = Math.addExact(session.fileBytes, access.fileBytes());
            int nextProcesses = Math.addExact(session.childProcesses, access.childProcesses());
            if (nextToolCalls > policy.maxToolCalls()
                    || nextNetworkCalls > policy.maxNetworkCalls()
                    || nextFileReads > policy.maxFileReads()
                    || nextFileWrites > policy.maxFileWrites()
                    || nextFileBytes > policy.maxFileBytes()
                    || nextProcesses > policy.maxChildProcesses()) {
                throw violation("RESOURCE_BUDGET_EXCEEDED", "Sandbox resource budget would be exceeded");
            }
            session.toolCalls = nextToolCalls;
            session.networkCalls = nextNetworkCalls;
            session.fileReads = nextFileReads;
            session.fileWrites = nextFileWrites;
            session.fileBytes = nextFileBytes;
            session.childProcesses = nextProcesses;
            return new AgentAuthorization(session.sessionId, session.level, session.operator, tool,
                    policy.policyVersion(), session.root, now, session.expiresAt);
        }
    }

    public AgentSandboxSession revoke(String sessionId, String reason) {
        MutableSession session = sessions.get(requireText(sessionId, "sessionId"));
        if (session == null) throw violation("SESSION_UNKNOWN", "Sandbox session is unknown or was invalidated");
        requireText(reason, "reason");
        synchronized (session) {
            session.revoked = true;
            return session.snapshot();
        }
    }

    public SandboxBudgetPolicy policy() {
        return policy;
    }

    private void validatePaths(Path root, SandboxAccessRequest access) {
        access.fileReads().forEach(path -> requireWithinRoot(root, path));
        access.fileWrites().forEach(path -> requireWithinRoot(root, path));
    }

    private void requireWithinRoot(Path root, Path requested) {
        Path resolved = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).normalize();
        if (!resolved.startsWith(root)) {
            throw violation("SANDBOX_PATH_ESCAPE", "File access escaped the session sandbox root");
        }
    }

    private static Map<AgentLevel, Set<AgentToolName>> allowlist() {
        EnumMap<AgentLevel, Set<AgentToolName>> values = new EnumMap<>(AgentLevel.class);
        values.put(AgentLevel.L1, EnumSet.of(
                AgentToolName.GENERATE_FALSIFIABLE_HYPOTHESIS,
                AgentToolName.GENERATE_EXPERIMENT_PLAN));
        values.put(AgentLevel.L2, EnumSet.of(
                AgentToolName.SUBMIT_PAPER_PROPOSAL,
                AgentToolName.SUBMIT_SHADOW_PROPOSAL));
        return Map.copyOf(values);
    }

    private AgentPolicyViolationException violation(String code, String message) {
        return new AgentPolicyViolationException(code, message);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static final class MutableSession {
        private final String sessionId;
        private final AgentLevel level;
        private final String operator;
        private final Path root;
        private final Instant createdAt;
        private final Instant expiresAt;
        private boolean revoked;
        private int toolCalls;
        private int networkCalls;
        private int fileReads;
        private int fileWrites;
        private long fileBytes;
        private int childProcesses;

        private MutableSession(String sessionId, AgentLevel level, String operator, Path root,
                               Instant createdAt, Instant expiresAt) {
            this.sessionId = sessionId;
            this.level = level;
            this.operator = operator;
            this.root = root;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        private AgentSandboxSession snapshot() {
            return new AgentSandboxSession(sessionId, level, operator, root, createdAt, expiresAt,
                    revoked, toolCalls);
        }
    }
}
