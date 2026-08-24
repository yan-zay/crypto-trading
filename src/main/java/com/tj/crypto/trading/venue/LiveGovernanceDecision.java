package com.tj.crypto.trading.venue;

import com.tj.crypto.governance.evidence.GovernanceSubject;

import java.util.Locale;
import java.util.regex.Pattern;

/** Append-only authorization fact persisted atomically with the OMS command and risk reservation. */
public record LiveGovernanceDecision(String decisionId,
                                     String clientOrderId,
                                     String commandSha256,
                                     String exchange,
                                     String accountId,
                                     String strategyId,
                                     String releaseAssignmentId,
                                     long releaseAssignmentVersion,
                                     String strategyArtifactSha256,
                                     GovernanceSubject governanceSubject,
                                     String evidenceSetSha256,
                                     String evidenceReferences,
                                     String approvalRequestId,
                                     String requestedBy,
                                     long authorizedAtMs,
                                     long expiresAtMs) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public LiveGovernanceDecision {
        decisionId = required(decisionId, "decisionId");
        clientOrderId = required(clientOrderId, "clientOrderId");
        commandSha256 = hash(commandSha256, "commandSha256");
        exchange = required(exchange, "exchange").toUpperCase(Locale.ROOT);
        accountId = required(accountId, "accountId");
        strategyId = required(strategyId, "strategyId");
        releaseAssignmentId = required(releaseAssignmentId, "releaseAssignmentId");
        if (releaseAssignmentVersion < 0) throw new IllegalArgumentException("releaseAssignmentVersion is invalid");
        strategyArtifactSha256 = hash(strategyArtifactSha256, "strategyArtifactSha256");
        if (governanceSubject == null
                || !strategyArtifactSha256.equals(governanceSubject.strategyArtifactSha256())) {
            throw new IllegalArgumentException("governance subject does not bind the strategy artifact");
        }
        evidenceSetSha256 = hash(evidenceSetSha256, "evidenceSetSha256");
        evidenceReferences = required(evidenceReferences, "evidenceReferences");
        approvalRequestId = required(approvalRequestId, "approvalRequestId");
        requestedBy = required(requestedBy, "requestedBy");
        if (authorizedAtMs <= 0 || expiresAtMs <= authorizedAtMs) {
            throw new IllegalArgumentException("authorization timestamps are invalid");
        }
    }

    public String governanceSubjectSha256() {
        return governanceSubject.sha256();
    }

    private static String hash(String value, String name) {
        String normalized = required(value, name).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
