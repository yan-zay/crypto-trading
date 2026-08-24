package com.tj.crypto.governance.evidence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class MediumTermReadinessGateTest {
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final String HASH = "b".repeat(64);
    private static final GovernanceSubject SUBJECT = new GovernanceSubject(
            "desk-a", "PRO", "account:testnet", "0123456789abcdef0123456789abcdef01234567",
            "1".repeat(64), "sha256:" + "2".repeat(64), "3".repeat(64),
            "4".repeat(64), "5".repeat(64), "hsm://signing/key-1", "run-1", "policy-1");

    @Test
    void missingExternalEvidenceKeepsSystemNoGo() {
        MediumTermReadinessGate gate = new MediumTermReadinessGate(new MemoryRepository());

        ReadinessDecision result = gate.evaluate(SUBJECT, 40 * DAY);

        assertThat(result.ready()).isFalse();
        assertThat(result.blockers()).anyMatch(value -> value.contains("KMS_KEY_CUSTODY"));
        assertThat(result.acceptedEvidence()).isEmpty();
    }

    @Test
    void refusesCompressedSevenDayAndForwardWindows() {
        MemoryRepository repository = completeRepository(40 * DAY);
        repository.values.put(ControlKind.SOAK_7D,
                verified(ControlKind.SOAK_7D, 39 * DAY, 40 * DAY, 50 * DAY));
        repository.values.put(ControlKind.FORWARD_PAPER_30D,
                verified(ControlKind.FORWARD_PAPER_30D, 20 * DAY, 40 * DAY, 80 * DAY));

        ReadinessDecision result = trustedGate(repository).evaluate(SUBJECT, 40 * DAY);

        assertThat(result.ready()).isFalse();
        assertThat(result.blockers()).anyMatch(value -> value.contains("SOAK_7D"));
        assertThat(result.blockers()).anyMatch(value -> value.contains("FORWARD_PAPER_30D"));
    }

    @Test
    void acceptsOnlyCompleteUnexpiredObservationSet() {
        ReadinessDecision result = trustedGate(completeRepository(40 * DAY))
                .evaluate(SUBJECT, 40 * DAY);

        assertThat(result.ready()).isTrue();
        assertThat(result.blockers()).isEmpty();
        assertThat(result.acceptedEvidence()).hasSize(ControlKind.values().length);
    }

    @Test
    void signedStatusFailureAndGenericEnvelopeCannotPass() {
        MemoryRepository repository = completeRepository(40 * DAY);
        ReadinessDecision statusMismatch = trustedGate(repository, (evidence, claims) ->
                claims.put("status", EvidenceStatus.FAILED.name())).evaluate(SUBJECT, 40 * DAY);
        ReadinessDecision genericEnvelope = trustedGate(repository, (evidence, claims) -> {
            claims.keySet().removeIf(name -> name.equals("schemaVersion")
                    || name.equals("outcome") || name.startsWith("measure.")
                    || name.startsWith("threshold."));
        }).evaluate(SUBJECT, 40 * DAY);

        assertThat(statusMismatch.ready()).isFalse();
        assertThat(statusMismatch.blockers()).anyMatch(value -> value.contains("status"));
        assertThat(genericEnvelope.ready()).isFalse();
        assertThat(genericEnvelope.acceptedEvidence()).isEmpty();
    }

    private static MemoryRepository completeRepository(long completedAt) {
        MemoryRepository repository = new MemoryRepository();
        for (ControlKind kind : ControlKind.values()) {
            long requiredWindow = switch (kind) {
                case SOAK_24H -> DAY;
                case PRIVATE_STREAM_STABILITY, SOAK_7D, CHAOS -> 7 * DAY;
                case FORWARD_PAPER_30D -> 30 * DAY;
                default -> 1;
            };
            repository.values.put(kind,
                    verified(kind, completedAt - requiredWindow, completedAt, completedAt + 30 * DAY));
        }
        return repository;
    }

    private static ControlEvidence verified(ControlKind kind, long start, long complete, long expiry) {
        return new ControlEvidence("e-" + kind, kind, "desk-a", "PRO", SUBJECT.sha256(),
                SUBJECT.policyVersion(), EvidenceStatus.VERIFIED,
                "owner", "external-auditor", start, complete, expiry, complete,
                "evidence://immutable/" + kind + ".json", HASH, "", "reviewed");
    }

    private static MediumTermReadinessGate trustedGate(MemoryRepository repository) {
        return trustedGate(repository, (evidence, claims) -> { });
    }

    private static MediumTermReadinessGate trustedGate(
            MemoryRepository repository,
            BiConsumer<ControlEvidence, Map<String, String>> claimCustomizer) {
        ExternalEvidenceVerifier verifier = (evidence, subject, nowMs) -> {
            Map<String, String> claims = new java.util.HashMap<>();
            claims.put("audience", "crypto-trading-governance-v1");
            claims.put("evidenceId", evidence.evidenceId());
            claims.put("kind", evidence.kind().name());
            claims.put("scope", evidence.scope());
            claims.put("environment", evidence.environment());
            claims.put("subjectSha256", evidence.subjectSha256());
            claims.put("policyVersion", evidence.policyVersion());
            claims.put("artifactUri", evidence.artifactUri());
            claims.put("owner", evidence.owner());
            claims.put("issuer", evidence.issuer());
            claims.put("startedAtMs", Long.toString(evidence.startedAtMs()));
            claims.put("completedAtMs", Long.toString(evidence.completedAtMs()));
            claims.put("expiresAtMs", Long.toString(evidence.expiresAtMs()));
            claims.put("registeredAtMs", Long.toString(evidence.registeredAtMs()));
            claims.put("predecessorEvidenceId", evidence.predecessorEvidenceId());
            claims.put("continuous", "true");
            claims.put("heartbeatCount", "10000");
            claims.put("maxGapMs", "60000");
            claims.put("unexplainedIncidentCount", "0");
            claims.put("chaosEventCount", "12");
            addPassingOutcomeClaims(evidence, claims);
            claimCustomizer.accept(evidence, claims);
            return new EvidenceArtifactVerification(true, "test-signature-verifier",
                    evidence.issuer(), "test-key-1", "Ed25519", evidence.artifactSha256(),
                    nowMs, claims, java.util.List.of());
        };
        com.tj.crypto.governance.security.KeyCustodyPolicy custodyPolicy =
                new com.tj.crypto.governance.security.KeyCustodyPolicy(
                        (subject, artifactSha256, nowMs) ->
                                new com.tj.crypto.governance.security.KeyCustodyVerification(
                                        true, "test-kms-verifier",
                                        new com.tj.crypto.governance.security.KeyCustodyAttestation(
                                                com.tj.crypto.governance.security.CustodyProvider.HSM,
                                                subject.keyReference(), subject.environment(), true, true,
                                                true, true, nowMs - 1_000, nowMs - DAY,
                                                nowMs - DAY, nowMs + 60_000, artifactSha256),
                                        java.util.List.of()));
        return new MediumTermReadinessGate(repository, verifier,
                custodyPolicy);
    }

    private static void addPassingOutcomeClaims(ControlEvidence evidence,
                                                Map<String, String> claims) {
        claims.put("schemaVersion", "governance-control-evidence/"
                + evidence.kind().name().toLowerCase(java.util.Locale.ROOT) + "/v1");
        claims.put("status", EvidenceStatus.VERIFIED.name());
        claims.put("outcome", evidence.kind() == ControlKind.LEGAL_APPROVAL
                ? "APPROVED" : "PASS");
        switch (evidence.kind()) {
            case LEGAL_APPROVAL -> {
                claims.put("measure.decision", "APPROVED");
                claims.put("threshold.requiredDecision", "APPROVED");
            }
            case KMS_KEY_CUSTODY -> {
                claims.put("measure.nonExportableKeyVerified", "true");
                claims.put("threshold.requireNonExportableKey", "true");
            }
            case MFA -> putAtLeast(claims, "successfulChallenges", 2);
            case DUAL_APPROVAL -> putAtLeast(claims, "distinctApprovers", 2);
            case IP_ALLOWLIST -> putAtMost(claims, "unauthorizedSourceCount", 0);
            case EXTERNAL_ALERT_DELIVERY -> putAtLeast(claims, "deliveredAlertCount", 1);
            case BACKUP_PITR_RESTORE -> putAtLeast(claims, "successfulRestoreCount", 1);
            case RPO_RTO -> {
                claims.put("measure.rpoMs", "1000");
                claims.put("threshold.maximumRpoMs", "2000");
                claims.put("measure.rtoMs", "2000");
                claims.put("threshold.maximumRtoMs", "3000");
            }
            case VENUE_DEMO_CONTRACT -> {
                putAtLeast(claims, "passedScenarioCount", 12);
                claims.put("threshold.requiredScenarioCount", "12");
                putAtMost(claims, "failedScenarioCount", 0);
            }
            case PRIVATE_STREAM_STABILITY -> putDuration(claims, "connectedDurationMs",
                    7 * DAY);
            case SOAK_24H -> putDuration(claims, "observedDurationMs", DAY);
            case SOAK_7D -> putDuration(claims, "observedDurationMs", 7 * DAY);
            case CHAOS -> {
                putAtLeast(claims, "chaosEventCount", 12);
                claims.put("threshold.minimumChaosEventCount", "1");
                putAtMost(claims, "unrecoveredIncidentCount", 0);
            }
            case FORWARD_PAPER_30D -> putDuration(claims, "observedDurationMs", 30 * DAY);
        }
    }

    private static void putAtLeast(Map<String, String> claims, String name, long value) {
        claims.put("measure." + name, Long.toString(value));
        claims.put("threshold.minimum" + Character.toUpperCase(name.charAt(0))
                + name.substring(1), Long.toString(value));
    }

    private static void putAtMost(Map<String, String> claims, String name, long value) {
        claims.put("measure." + name, Long.toString(value));
        claims.put("threshold.maximum" + Character.toUpperCase(name.charAt(0))
                + name.substring(1), Long.toString(value));
    }

    private static void putDuration(Map<String, String> claims, String measureName, long value) {
        claims.put("measure." + measureName, Long.toString(value));
        claims.put("threshold.minimum" + Character.toUpperCase(measureName.charAt(0))
                + measureName.substring(1), Long.toString(value));
    }

    private static final class MemoryRepository implements ControlEvidenceRepository {
        private final Map<ControlKind, ControlEvidence> values = new EnumMap<>(ControlKind.class);

        @Override
        public void append(ControlEvidence evidence) {
            if (values.putIfAbsent(evidence.kind(), evidence) != null) {
                throw new IllegalStateException("immutable");
            }
        }

        @Override
        public Optional<ControlEvidence> findLatest(ControlKind kind, String scope, String environment,
                                                    String subjectSha256, String policyVersion) {
            return Optional.ofNullable(values.get(kind));
        }
    }
}
