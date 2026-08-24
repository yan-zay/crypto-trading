package com.tj.crypto.strategy.lifecycle;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure deterministic policy evaluation; no model or network decision participates. */
final class StrategyLifecyclePolicy {
    static final int REQUIRED_APPROVERS = 2;

    private static final Set<StrategyEvidenceType> SHADOW_EVIDENCE = EnumSet.of(
            StrategyEvidenceType.REPRODUCIBILITY,
            StrategyEvidenceType.RESEARCH,
            StrategyEvidenceType.PARITY,
            StrategyEvidenceType.RISK,
            StrategyEvidenceType.RECONCILIATION);

    private static final Set<StrategyEvidenceType> LIVE_EVIDENCE =
            Set.copyOf(EnumSet.allOf(StrategyEvidenceType.class));

    PolicyAssessment assess(StrategyLifecycleStage target,
                            List<StrategyEvidenceSnapshot> snapshots,
                            Instant now) {
        Set<StrategyEvidenceType> required = switch (target) {
            case SHADOW -> SHADOW_EVIDENCE;
            case CANARY, PROMOTED -> LIVE_EVIDENCE;
            default -> Set.of();
        };
        Map<StrategyEvidenceType, StrategyEvidenceSnapshot> latest = latest(snapshots);
        String setDigest = evidenceSetDigest(latest.values());
        for (StrategyEvidenceType type : required.stream().sorted().toList()) {
            StrategyEvidenceSnapshot evidence = latest.get(type);
            if (evidence == null) return denied(type, LifecycleReason.EVIDENCE_MISSING,
                    "required evidence is missing: " + type, setDigest, required);
            if (evidence.outcome() != EvidenceOutcome.PASS) {
                return denied(type, LifecycleReason.EVIDENCE_FAILED,
                        "latest evidence is not PASS: " + type, setDigest, required);
            }
            if (evidence.capturedAt().isAfter(now) || !evidence.expiresAt().isAfter(now)) {
                return denied(type, LifecycleReason.EVIDENCE_EXPIRED,
                        "latest evidence is expired or not yet valid: " + type, setDigest, required);
            }
        }
        return new PolicyAssessment(true, LifecycleReason.ACCEPTED, "all required evidence is current",
                setDigest, Set.copyOf(required));
    }

    String evidenceSetDigest(List<StrategyEvidenceSnapshot> snapshots) {
        return evidenceSetDigest(latest(snapshots).values());
    }

    private Map<StrategyEvidenceType, StrategyEvidenceSnapshot> latest(
            List<StrategyEvidenceSnapshot> snapshots) {
        EnumMap<StrategyEvidenceType, StrategyEvidenceSnapshot> latest =
                new EnumMap<>(StrategyEvidenceType.class);
        if (snapshots == null) return latest;
        for (StrategyEvidenceSnapshot candidate : snapshots) {
            if (candidate == null) continue;
            latest.merge(candidate.type(), candidate,
                    (left, right) -> left.recordedAt().isAfter(right.recordedAt()) ? left : right);
        }
        return latest;
    }

    private String evidenceSetDigest(java.util.Collection<StrategyEvidenceSnapshot> snapshots) {
        String canonical = snapshots.stream()
                .sorted(Comparator.comparing(snapshot -> snapshot.type().name()))
                .map(snapshot -> String.join("|", snapshot.type().name(), snapshot.outcome().name(),
                        snapshot.evidenceSha256(), Long.toString(snapshot.capturedAt().toEpochMilli()),
                        Long.toString(snapshot.expiresAt().toEpochMilli()), snapshot.recordedBy()))
                .collect(Collectors.joining("\n"));
        return Hashes.sha256(canonical);
    }

    private PolicyAssessment denied(StrategyEvidenceType type, LifecycleReason fallback,
                                    String detail, String digest, Set<StrategyEvidenceType> required) {
        LifecycleReason reason = switch (type) {
            case LEGAL -> LifecycleReason.LEGAL_GATE_BLOCKED;
            case SECURITY -> LifecycleReason.SECURITY_GATE_BLOCKED;
            case OPERATIONS -> LifecycleReason.OPERATIONS_GATE_BLOCKED;
            case LIVE_TRUTH -> LifecycleReason.LIVE_TRUTH_GATE_BLOCKED;
            default -> fallback;
        };
        return new PolicyAssessment(false, reason, detail, digest, Set.copyOf(required));
    }
}
