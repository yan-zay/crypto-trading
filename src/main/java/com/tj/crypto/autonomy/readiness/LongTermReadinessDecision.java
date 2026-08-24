package com.tj.crypto.autonomy.readiness;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only readiness result. It is not an order, capability token, execution permit or approval.
 * Its constructor is private so callers cannot manufacture a forged GO record.
 */
public final class LongTermReadinessDecision {
    private final AutonomyLevel autonomyLevel;
    private final ReadinessVerdict verdict;
    private final String readinessSubjectSha256;
    private final Instant decidedAt;
    private final Instant expiresAt;
    private final String evidenceSetSha256;
    private final String prerequisiteDecisionSha256;
    private final Map<LongTermControl, AcceptedEvidence> acceptedEvidence;
    private final List<String> blockers;

    private LongTermReadinessDecision(AutonomyLevel autonomyLevel,
                                      ReadinessVerdict verdict,
                                      String readinessSubjectSha256,
                                      Instant decidedAt,
                                      Instant expiresAt,
                                      String evidenceSetSha256,
                                      String prerequisiteDecisionSha256,
                                      Map<LongTermControl, AcceptedEvidence> acceptedEvidence,
                                      List<String> blockers) {
        this.autonomyLevel = Objects.requireNonNull(autonomyLevel, "autonomyLevel");
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.readinessSubjectSha256 = ReadinessValues.hash(
                readinessSubjectSha256, "readinessSubjectSha256");
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.evidenceSetSha256 = ReadinessValues.hash(
                evidenceSetSha256, "evidenceSetSha256");
        this.prerequisiteDecisionSha256 = ReadinessValues.hash(
                prerequisiteDecisionSha256, "prerequisiteDecisionSha256");
        EnumMap<LongTermControl, AcceptedEvidence> accepted =
                new EnumMap<>(LongTermControl.class);
        if (acceptedEvidence != null) accepted.putAll(acceptedEvidence);
        this.acceptedEvidence = Map.copyOf(accepted);
        this.blockers = List.copyOf(blockers == null ? List.of() : blockers);
        if (verdict == ReadinessVerdict.GO) {
            if (!this.blockers.isEmpty()
                    || this.acceptedEvidence.size() != LongTermControl.values().length
                    || !expiresAt.isAfter(decidedAt)) {
                throw new IllegalArgumentException(
                        "GO requires every control, no blockers and an unexpired decision");
            }
        } else if (this.blockers.isEmpty()) {
            throw new IllegalArgumentException("NO_GO requires at least one blocker");
        }
    }

    static LongTermReadinessDecision create(AutonomyLevel autonomyLevel,
                                            ReadinessVerdict verdict,
                                            String readinessSubjectSha256,
                                            Instant decidedAt,
                                            Instant expiresAt,
                                            String evidenceSetSha256,
                                            String prerequisiteDecisionSha256,
                                            Map<LongTermControl, AcceptedEvidence> acceptedEvidence,
                                            List<String> blockers) {
        return new LongTermReadinessDecision(autonomyLevel, verdict, readinessSubjectSha256,
                decidedAt, expiresAt, evidenceSetSha256, prerequisiteDecisionSha256,
                acceptedEvidence, blockers);
    }

    public AutonomyLevel autonomyLevel() {
        return autonomyLevel;
    }

    public ReadinessVerdict verdict() {
        return verdict;
    }

    public String readinessSubjectSha256() {
        return readinessSubjectSha256;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public String evidenceSetSha256() {
        return evidenceSetSha256;
    }

    public String prerequisiteDecisionSha256() {
        return prerequisiteDecisionSha256;
    }

    public Map<LongTermControl, AcceptedEvidence> acceptedEvidence() {
        return acceptedEvidence;
    }

    public List<String> blockers() {
        return blockers;
    }

    /** A cached GO is never considered ready outside its half-open validity interval. */
    public boolean readyAt(Instant now) {
        return now != null && verdict == ReadinessVerdict.GO
                && !now.isBefore(decidedAt) && now.isBefore(expiresAt);
    }

    public boolean wasGoAtDecisionTime() {
        return verdict == ReadinessVerdict.GO;
    }
}
