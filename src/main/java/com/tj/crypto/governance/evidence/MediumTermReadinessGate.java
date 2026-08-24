package com.tj.crypto.governance.evidence;

import com.tj.crypto.governance.security.KeyCustodyDecision;
import com.tj.crypto.governance.security.KeyCustodyPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed gate for external and time-based medium-term acceptance evidence.
 *
 * <p>This service does not run KMS, MFA, venue or disaster-recovery infrastructure. It only
 * verifies that independently produced, checksummed and unexpired evidence is registered.</p>
 */
@Service
public class MediumTermReadinessGate {
    /** Every declared capital-risk control is mandatory; adding an enum cannot silently weaken the gate. */
    private static final Set<ControlKind> REQUIRED = EnumSet.allOf(ControlKind.class);

    private static final Map<ControlKind, Duration> MINIMUM_OBSERVATION = Map.of(
            ControlKind.PRIVATE_STREAM_STABILITY, Duration.ofDays(7),
            ControlKind.SOAK_24H, Duration.ofHours(24),
            ControlKind.SOAK_7D, Duration.ofDays(7),
            ControlKind.CHAOS, Duration.ofDays(7),
            ControlKind.FORWARD_PAPER_30D, Duration.ofDays(30));

    private final ControlEvidenceRepository repository;
    private final ExternalEvidenceVerifier externalVerifier;
    private final KeyCustodyPolicy keyCustodyPolicy;

    @Autowired
    public MediumTermReadinessGate(ControlEvidenceRepository repository,
                                   ExternalEvidenceVerifier externalVerifier,
                                   KeyCustodyPolicy keyCustodyPolicy) {
        this.repository = repository;
        this.externalVerifier = externalVerifier;
        this.keyCustodyPolicy = keyCustodyPolicy;
    }

    /** Compatibility constructor remains fail-closed because no external trust root is supplied. */
    MediumTermReadinessGate(ControlEvidenceRepository repository) {
        this(repository, (evidence, subject, nowMs) -> EvidenceArtifactVerification.untrusted(
                "no trusted external evidence verifier is configured"), new KeyCustodyPolicy());
    }

    public ReadinessDecision evaluate(GovernanceSubject subject, long nowMs) {
        List<String> blockers = new ArrayList<>();
        Map<ControlKind, String> accepted = new EnumMap<>(ControlKind.class);
        for (ControlKind kind : REQUIRED) {
            ControlEvidence evidence;
            try {
                evidence = repository.findLatest(kind, subject.scope(), subject.environment(),
                        subject.sha256(), subject.policyVersion()).orElse(null);
            } catch (RuntimeException queryFailure) {
                blockers.add(kind + ": evidence query failed");
                continue;
            }
            if (evidence == null) {
                blockers.add(kind + ": NOT_VERIFIED");
                continue;
            }
            if (evidence.kind() != kind
                    || !evidence.scope().equals(subject.scope())
                    || !evidence.environment().equals(subject.environment())
                    || !evidence.subjectSha256().equals(subject.sha256())
                    || !evidence.policyVersion().equals(subject.policyVersion())) {
                blockers.add(kind + ": evidence is bound to a different candidate, stream or policy");
                continue;
            }
            if (!evidence.isActiveAt(nowMs)) {
                blockers.add(kind + ": " + evidence.status() + " or expired");
                continue;
            }
            Duration minimum = MINIMUM_OBSERVATION.get(kind);
            if (minimum != null && evidence.observedDurationMs() < minimum.toMillis()) {
                blockers.add(kind + ": observation window shorter than " + minimum);
                continue;
            }
            EvidenceArtifactVerification verification;
            try {
                verification = externalVerifier.verify(evidence, subject, nowMs);
            } catch (RuntimeException verifierFailure) {
                blockers.add(kind + ": external verification failed");
                continue;
            }
            List<String> verificationBlockers = verifyRequiredClaims(
                    evidence, subject, verification, nowMs);
            if (!verificationBlockers.isEmpty()) {
                verificationBlockers.forEach(reason -> blockers.add(kind + ": " + reason));
                continue;
            }
            if (kind == ControlKind.KMS_KEY_CUSTODY) {
                KeyCustodyDecision custody = keyCustodyPolicy.evaluate(
                        subject, evidence.artifactSha256(), nowMs);
                if (!custody.approved()) {
                    custody.blockers().forEach(reason -> blockers.add(kind + ": " + reason));
                    continue;
                }
            }
            List<String> outcomeBlockers = ControlEvidenceOutcomeSchema.verify(
                    kind, verification.claims());
            if (!outcomeBlockers.isEmpty()) {
                outcomeBlockers.forEach(reason -> blockers.add(kind + ": " + reason));
                continue;
            }
            if (MINIMUM_OBSERVATION.containsKey(kind)) {
                List<String> continuityBlockers = verifyContinuityClaims(kind, verification.claims());
                if (!continuityBlockers.isEmpty()) {
                    continuityBlockers.forEach(reason -> blockers.add(kind + ": " + reason));
                    continue;
                }
            }
            accepted.put(kind, evidence.evidenceId());
        }
        return new ReadinessDecision(blockers.isEmpty(), blockers, accepted);
    }

    public ReadinessDecision evaluate(GovernanceSubject subject, Clock clock) {
        return evaluate(subject, clock.millis());
    }

    /** A caller that omits exact build/account/config context can never open the gate. */
    public ReadinessDecision evaluate(String scope, String environment, long nowMs) {
        return new ReadinessDecision(false,
                List.of("GOVERNANCE_SUBJECT: exact candidate manifest is required"), Map.of());
    }

    private List<String> verifyRequiredClaims(ControlEvidence evidence,
                                              GovernanceSubject subject,
                                              EvidenceArtifactVerification verification,
                                              long nowMs) {
        if (verification == null || !verification.trusted()) {
            return verification == null || verification.blockers().isEmpty()
                    ? List.of("artifact is NOT_VERIFIED") : verification.blockers();
        }
        Map<String, String> claims = verification.claims();
        Map<String, String> expected = Map.of(
                "audience", "crypto-trading-governance-v1",
                "evidenceId", evidence.evidenceId(),
                "kind", evidence.kind().name(),
                "scope", evidence.scope(),
                "environment", evidence.environment(),
                "subjectSha256", evidence.subjectSha256(),
                "policyVersion", evidence.policyVersion(),
                "artifactUri", evidence.artifactUri(),
                "owner", evidence.owner(),
                "issuer", evidence.issuer());
        List<String> blockers = new ArrayList<>();
        Map<String, String> temporalExpected = Map.of(
                "startedAtMs", Long.toString(evidence.startedAtMs()),
                "completedAtMs", Long.toString(evidence.completedAtMs()),
                "expiresAtMs", Long.toString(evidence.expiresAtMs()),
                "registeredAtMs", Long.toString(evidence.registeredAtMs()),
                "predecessorEvidenceId", evidence.predecessorEvidenceId());
        expected.forEach((name, value) -> {
            if (!value.equals(claims.get(name))) blockers.add("signed claim mismatch: " + name);
        });
        temporalExpected.forEach((name, value) -> {
            if (!value.equals(claims.get(name))) blockers.add("signed claim mismatch: " + name);
        });
        if (!evidence.issuer().equals(verification.verifiedIssuer())) {
            blockers.add("verified issuer mismatch");
        }
        if (!evidence.artifactSha256().equals(verification.recomputedArtifactSha256())) {
            blockers.add("recomputed artifact SHA-256 mismatch");
        }
        if (verification.verifiedAtMs() < evidence.registeredAtMs()
                || verification.verifiedAtMs() > nowMs) {
            blockers.add("artifact verification timestamp is outside the accepted window");
        }
        if (!subject.sha256().equals(claims.get("subjectSha256"))) {
            blockers.add("signed artifact is not bound to the exact governance subject");
        }
        return blockers;
    }

    private List<String> verifyContinuityClaims(ControlKind kind, Map<String, String> claims) {
        List<String> blockers = new ArrayList<>();
        if (!"true".equals(claims.get("continuous"))) blockers.add("run was not continuous");
        if (parseLongOr(claims, "heartbeatCount", 0) <= 0) blockers.add("no signed heartbeats");
        if (parseLongOr(claims, "maxGapMs", Long.MAX_VALUE) > Duration.ofMinutes(5).toMillis()) {
            blockers.add("heartbeat gap exceeds five minutes");
        }
        if (parseLongOr(claims, "unexplainedIncidentCount", Long.MAX_VALUE) != 0) {
            blockers.add("run contains unexplained incidents");
        }
        if (kind == ControlKind.CHAOS && parseLongOr(claims, "chaosEventCount", 0) <= 0) {
            blockers.add("no signed chaos events");
        }
        return blockers;
    }

    private long parseLongOr(Map<String, String> claims, String name, long fallback) {
        try {
            return Long.parseLong(claims.get(name));
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }
}
