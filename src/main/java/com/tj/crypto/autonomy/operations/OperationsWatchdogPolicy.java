package com.tj.crypto.autonomy.operations;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Immutable trust and freshness policy supplied by the deterministic host. */
public record OperationsWatchdogPolicy(
        String subjectSha256,
        String policySha256,
        Map<OperationsObservationKind, String> authoritativeSources,
        Set<String> trustedHeadAuthorityIds,
        Set<String> trustedObservationVerifierIds,
        Set<String> trustedOperatorVerifierIds,
        long maximumObservationTtlMs,
        long maximumRecoveryTtlMs,
        long maximumApprovalTtlMs,
        long maximumActionIntentTtlMs,
        int requiredRecoveryApprovers
) {
    public static final String RECOVERY_APPROVER_ROLE = "OPERATIONS_RECOVERY_APPROVER";
    public static final String RECOVERY_COMPLETER_ROLE = "OPERATIONS_RECOVERY_COMPLETER";
    public static final String MANUAL_TAKEOVER_ROLE = "OPERATIONS_MANUAL_TAKEOVER";
    public static final String OPERATOR_AUDIENCE = "crypto-trading-operations-watchdog-v1";

    public OperationsWatchdogPolicy {
        if (!OperationsHashing.isSha256(subjectSha256)) {
            throw new IllegalArgumentException("subjectSha256 must be a lowercase SHA-256");
        }
        if (!OperationsHashing.isSha256(policySha256)) {
            throw new IllegalArgumentException("policySha256 must be a lowercase SHA-256");
        }
        if (authoritativeSources == null
                || !authoritativeSources.keySet().equals(EnumSet.allOf(OperationsObservationKind.class))) {
            throw new IllegalArgumentException("every watchdog fact kind needs an authoritative source");
        }
        EnumMap<OperationsObservationKind, String> sourceCopy =
                new EnumMap<>(OperationsObservationKind.class);
        authoritativeSources.forEach((kind, source) -> {
            if (kind == null || !OperationsHashing.safeText(source, 128)) {
                throw new IllegalArgumentException("authoritative source is invalid");
            }
            sourceCopy.put(kind, source);
        });
        authoritativeSources = Map.copyOf(sourceCopy);
        if (trustedHeadAuthorityIds == null || trustedHeadAuthorityIds.isEmpty()
                || trustedHeadAuthorityIds.stream()
                .anyMatch(value -> !OperationsHashing.safeText(value, 128))) {
            throw new IllegalArgumentException("at least one trusted durable head authority is required");
        }
        trustedHeadAuthorityIds = Set.copyOf(trustedHeadAuthorityIds);
        if (trustedObservationVerifierIds == null || trustedObservationVerifierIds.isEmpty()
                || trustedObservationVerifierIds.stream()
                .anyMatch(value -> !OperationsHashing.safeText(value, 128))) {
            throw new IllegalArgumentException("at least one trusted observation verifier is required");
        }
        trustedObservationVerifierIds = Set.copyOf(trustedObservationVerifierIds);
        if (trustedOperatorVerifierIds == null || trustedOperatorVerifierIds.isEmpty()
                || trustedOperatorVerifierIds.stream()
                .anyMatch(value -> !OperationsHashing.safeText(value, 128))) {
            throw new IllegalArgumentException("at least one trusted operator verifier is required");
        }
        trustedOperatorVerifierIds = Set.copyOf(trustedOperatorVerifierIds);
        if (maximumObservationTtlMs <= 0 || maximumRecoveryTtlMs <= 0
                || maximumApprovalTtlMs <= 0 || maximumActionIntentTtlMs <= 0) {
            throw new IllegalArgumentException("watchdog TTL limits must be positive");
        }
        if (requiredRecoveryApprovers < 2) {
            throw new IllegalArgumentException("recovery always requires at least two approvers");
        }
    }
}
