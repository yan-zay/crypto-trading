package com.tj.crypto.research.agent.policy;

import java.time.Duration;

/**
 * 沙盒硬预算。默认策略不允许网络和子进程，文件访问只允许会话根目录中的小型研究制品。
 */
public record SandboxBudgetPolicy(
        String policyVersion,
        int maxToolCalls,
        int maxNetworkCalls,
        int maxFileReads,
        int maxFileWrites,
        long maxFileBytes,
        int maxChildProcesses,
        Duration maxInvocationTime,
        Duration maxSessionTtl,
        Duration maxProposalTtl) {

    public static final String CURRENT_VERSION = "agent-sandbox-policy-v1";

    public SandboxBudgetPolicy {
        requireText(policyVersion, "policyVersion");
        if (maxToolCalls <= 0 || maxNetworkCalls < 0 || maxFileReads < 0 || maxFileWrites < 0
                || maxFileBytes < 0 || maxChildProcesses < 0) {
            throw new IllegalArgumentException("Sandbox budgets must be non-negative and maxToolCalls positive");
        }
        requirePositive(maxInvocationTime, "maxInvocationTime");
        requirePositive(maxSessionTtl, "maxSessionTtl");
        requirePositive(maxProposalTtl, "maxProposalTtl");
        if (maxProposalTtl.compareTo(maxSessionTtl) > 0) {
            throw new IllegalArgumentException("Proposal TTL cannot exceed session TTL");
        }
    }

    public static SandboxBudgetPolicy restrictiveDefault() {
        return new SandboxBudgetPolicy(
                CURRENT_VERSION,
                32,
                0,
                8,
                4,
                4L * 1024L * 1024L,
                0,
                Duration.ofSeconds(5),
                Duration.ofMinutes(30),
                Duration.ofMinutes(10));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
