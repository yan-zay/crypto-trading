package com.tj.crypto.research.experiment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentRegistryServiceTest {
    private InMemoryMapper mapper;
    private ExperimentRegistryService service;

    @BeforeEach
    void setUp() {
        mapper = new InMemoryMapper();
        service = new ExperimentRegistryService(mapper, certifiedEvidencePort());
    }

    @Test
    void registrationIsIdempotentButNeverAllowsKeyOrDigestOverwrite() {
        ExperimentFamilyRegistration command = registration("family-key", "hypothesis");

        ExperimentRegistrationReceipt created = service.register(command, "researcher");
        ExperimentRegistrationReceipt replayed = service.register(command, "someone-else");

        assertThat(replayed.registrationId()).isEqualTo(created.registrationId());
        assertThat(replayed.registrationSha256()).isEqualTo(created.registrationSha256());
        assertThat(replayed.idempotentReplay()).isTrue();
        assertThat(mapper.events).hasSize(3); // family + two frozen trial declarations

        assertThatThrownBy(() -> service.register(
                registration("family-key", "changed after looking at results"), "researcher"))
                .isInstanceOf(ExperimentRegistrationConflictException.class)
                .hasMessageContaining("different immutable experiment content");

        assertThatThrownBy(() -> service.register(
                registration("new-key", "hypothesis"), "researcher"))
                .isInstanceOf(ExperimentRegistrationConflictException.class)
                .hasMessageContaining("Identical immutable registration already exists");
    }

    @Test
    void refusesToStartUnlessEveryObservedReproductionInputMatchesExactly() {
        ExperimentRegistrationReceipt receipt = service.register(
                registration("family-key", "hypothesis"), "researcher");
        ExperimentReproductionInputs alteredSeed = inputs(receipt.registrationSha256(), 999L);

        ExperimentReproducibilityReport report = service.checkReproducibility(
                receipt.registrationId(), "trial-a", alteredSeed);

        assertThat(report.reproducible()).isFalse();
        assertThat(report.mismatches()).containsExactly("random seed");
        assertThatThrownBy(() -> service.startTrial(receipt.registrationId(), "trial-a",
                alteredSeed, "worker"))
                .isInstanceOf(ExperimentReproducibilityException.class)
                .hasMessageContaining("random seed");
        assertThat(mapper.events).hasSize(3);

        Map<String, String> extraArtifact = new LinkedHashMap<>(artifactDigests());
        extraArtifact.put("unregistered", "f".repeat(64));
        ExperimentReproducibilityReport extra = service.checkReproducibility(
                receipt.registrationId(), "trial-a",
                new ExperimentReproductionInputs(receipt.registrationSha256(), extraArtifact,
                        11L, "e".repeat(64), "model-none-v1", "prompt-none-v1"));
        assertThat(extra.mismatches()).contains("unregistered artifacts [unregistered]");
    }

    @Test
    void appendOnlyStateMachineKeepsSuccessfulAndFailedTrialsInOneAuditTrail() {
        ExperimentRegistrationReceipt receipt = service.register(
                registration("family-key", "hypothesis"), "researcher");

        service.startTrial(receipt.registrationId(), "trial-a",
                inputs(receipt.registrationSha256(), 11L), "worker-a");
        service.recordOutcome(receipt.registrationId(), "trial-a", ExperimentState.SUCCEEDED,
                null, "metrics persisted", result('9'), "worker-a");
        service.recordOutcome(receipt.registrationId(), "trial-b", ExperimentState.FAILED,
                "WORKER_SETUP_FAILED", "dependency could not be loaded", result('8'), "worker-b");

        ExperimentAuditTrail audit = service.audit(receipt.registrationId());
        assertThat(audit.completeTrialFamily()).isTrue();
        assertThat(audit.auditChainValid()).isTrue();
        assertThat(audit.familyState()).isEqualTo(ExperimentFamilyState.FAILED);
        assertThat(audit.trialStates()).containsEntry("trial-a", ExperimentState.SUCCEEDED)
                .containsEntry("trial-b", ExperimentState.FAILED);
        assertThat(audit.events()).extracting(ExperimentEventAudit::eventType)
                .contains("TRIAL_SUCCEEDED", "TRIAL_FAILED");

        ExperimentPromotionEvidence evidence = service.promotionEvidence(
                receipt.registrationId(), receipt.registrationSha256());
        assertThat(evidence.eligible()).isFalse();
        assertThat(evidence.allTrialsTerminal()).isTrue();
        assertThat(evidence.allTrialsSucceeded()).isFalse();
        assertThat(evidence.reasons()).contains(
                "not every pre-registered trial completed successfully");

        assertThatThrownBy(() -> service.startTrial(receipt.registrationId(), "trial-b",
                inputs(receipt.registrationSha256(), 22L), "worker-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED -> RUNNING");
    }

    @Test
    void promotionEvidenceRequiresDigestCompleteTerminalFamilyAndValidHashChain() {
        ExperimentRegistrationReceipt receipt = service.register(
                registration("family-key", "hypothesis"), "researcher");
        succeed(receipt, "trial-a", 11L, '8');
        succeed(receipt, "trial-b", 22L, '9');

        ExperimentPromotionEvidence eligible = service.promotionEvidence(
                receipt.registrationId(), receipt.registrationSha256());
        assertThat(eligible.eligible()).isTrue();
        assertThat(eligible.allTrialsTerminal()).isTrue();
        assertThat(eligible.allTrialsSucceeded()).isTrue();
        assertThat(eligible.reasons()).isEmpty();

        ExperimentPromotionEvidence wrongDigest = service.promotionEvidence(
                receipt.registrationId(), "f".repeat(64));
        assertThat(wrongDigest.eligible()).isFalse();
        assertThat(wrongDigest.reasons()).contains(
                "research registration SHA-256 does not match signed evidence");

        mapper.events.get(2).setEventSha256("0".repeat(64));
        ExperimentPromotionEvidence tampered = service.promotionEvidence(
                receipt.registrationId(), receipt.registrationSha256());
        assertThat(tampered.eligible()).isFalse();
        assertThat(tampered.auditChainValid()).isFalse();
    }

    @Test
    void promotionDefaultsToNotVerifiedWithoutSignedCertificationAdapter() {
        ExperimentRegistryService unverifiedService = new ExperimentRegistryService(mapper);
        ExperimentRegistrationReceipt receipt = service.register(
                registration("unverified-key", "hypothesis"), "researcher");
        succeed(receipt, "trial-a", 11L, '8');
        succeed(receipt, "trial-b", 22L, '9');

        ExperimentPromotionEvidence evidence = unverifiedService.promotionEvidence(
                receipt.registrationId(), receipt.registrationSha256());

        assertThat(evidence.eligible()).isFalse();
        assertThat(evidence.certification().verdict())
                .isEqualTo(ExperimentCertificationVerdict.NOT_VERIFIED);
        assertThat(evidence.reasons()).anyMatch(reason ->
                reason.contains("CERTIFICATION_NOT_VERIFIED"));
    }

    @Test
    void lifecycleCommandsAreIdempotentButRejectSameKeyWithDifferentContent() {
        ExperimentRegistrationReceipt receipt = service.register(
                registration("command-key", "hypothesis"), "researcher");
        ExperimentReproductionInputs observed = inputs(receipt.registrationSha256(), 11L);

        ExperimentEventAudit first = service.startTrial(receipt.registrationId(), "trial-a",
                observed, "start-command", "worker");
        ExperimentEventAudit replay = service.startTrial(receipt.registrationId(), "trial-a",
                observed, "start-command", "worker");
        assertThat(replay.eventId()).isEqualTo(first.eventId());

        assertThatThrownBy(() -> service.startTrial(receipt.registrationId(), "trial-a",
                observed, "start-command", "different-worker"))
                .isInstanceOf(ExperimentRegistrationConflictException.class)
                .hasMessageContaining("different immutable content");
    }

    @Test
    void missingOrPostSealDefinitionPollutionBlocksReproductionStartAndPromotion() {
        ExperimentRegistrationReceipt missingReceipt = service.register(
                registration("missing-definition", "missing core definition"), "researcher");
        mapper.artifacts.removeIf(row -> row.getRegistrationId().equals(
                        missingReceipt.registrationId())
                && row.getArtifactKind().equals(ExperimentArtifactKind.CODE.name()));

        ExperimentReproducibilityReport missing = service.checkReproducibility(
                missingReceipt.registrationId(), "trial-a",
                inputs(missingReceipt.registrationSha256(), 11L));
        assertThat(missing.reproducible()).isFalse();
        assertThat(missing.mismatches())
                .contains("complete pre-registered trial family",
                        "registration and audit hash-chain integrity");
        assertThatThrownBy(() -> service.startTrial(missingReceipt.registrationId(), "trial-a",
                inputs(missingReceipt.registrationSha256(), 11L), "worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete or its audit chain is invalid");
        assertThat(service.promotionEvidence(missingReceipt.registrationId(),
                missingReceipt.registrationSha256()).eligible()).isFalse();

        mapper = new InMemoryMapper();
        service = new ExperimentRegistryService(mapper, certifiedEvidencePort());
        ExperimentRegistrationReceipt pollutedReceipt = service.register(
                registration("polluted-definition", "post-seal definition pollution"),
                "researcher");
        ExperimentArtifactDO injected = new ExperimentArtifactDO();
        injected.setRegistrationId(pollutedReceipt.registrationId());
        injected.setArtifactId("post-selection-artifact");
        injected.setArtifactKind(ExperimentArtifactKind.OTHER.name());
        injected.setArtifactVersion("v1");
        injected.setArtifactUri("immutable://post-selection-artifact");
        injected.setArtifactSha256("9".repeat(64));
        injected.setMediaType("application/octet-stream");
        mapper.artifacts.add(injected);

        assertThatThrownBy(() -> service.startTrial(pollutedReceipt.registrationId(), "trial-a",
                inputs(pollutedReceipt.registrationSha256(), 11L), "worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit chain is invalid");
        ExperimentPromotionEvidence polluted = service.promotionEvidence(
                pollutedReceipt.registrationId(), pollutedReceipt.registrationSha256());
        assertThat(polluted.eligible()).isFalse();
        assertThat(polluted.auditChainValid()).isFalse();
    }

    @Test
    void cancelledTrialRemainsAuditedButCanNeverProducePromotionEvidence() {
        ExperimentRegistrationReceipt receipt = service.register(
                registration("cancelled-family", "cancelled trial family"), "researcher");
        succeed(receipt, "trial-a", 11L, '8');
        service.recordOutcome(receipt.registrationId(), "trial-b", ExperimentState.CANCELLED,
                "OPERATOR_CANCELLED", "resource budget exhausted", null, "worker");

        ExperimentAuditTrail audit = service.audit(receipt.registrationId());
        assertThat(audit.auditChainValid()).isTrue();
        assertThat(audit.trialStates()).containsEntry("trial-b", ExperimentState.CANCELLED);

        ExperimentPromotionEvidence evidence = service.promotionEvidence(
                receipt.registrationId(), receipt.registrationSha256());
        assertThat(evidence.allTrialsTerminal()).isTrue();
        assertThat(evidence.allTrialsSucceeded()).isFalse();
        assertThat(evidence.eligible()).isFalse();
        assertThat(evidence.reasons())
                .contains("not every pre-registered trial completed successfully");
    }

    @Test
    void terminalOutcomeRulesForceFailureReasonsAndSuccessfulResultDigests() {
        ExperimentRegistrationReceipt receipt = service.register(
                registration("family-key", "hypothesis"), "researcher");

        assertThatThrownBy(() -> service.recordOutcome(receipt.registrationId(), "trial-a",
                ExperimentState.SUCCEEDED, null, null, null, "worker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a SHA-256 result artifact");
        assertThatThrownBy(() -> service.recordOutcome(receipt.registrationId(), "trial-a",
                ExperimentState.FAILED, null, "failed", null, "worker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require a reasonCode");
    }

    private void succeed(ExperimentRegistrationReceipt receipt, String trialId, long seed,
                         char resultDigest) {
        service.startTrial(receipt.registrationId(), trialId,
                inputs(receipt.registrationSha256(), seed), "worker");
        service.recordOutcome(receipt.registrationId(), trialId, ExperimentState.SUCCEEDED,
                null, "complete", result(resultDigest), "worker");
    }

    private ExperimentFamilyRegistration registration(String key, String hypothesis) {
        return new ExperimentFamilyRegistration(key, "golden-family", hypothesis,
                "protocol-v1", "model-none-v1", "prompt-none-v1", 2,
                List.of(
                        artifact("dataset", ExperimentArtifactKind.DATASET, 'a'),
                        artifact("code", ExperimentArtifactKind.CODE, 'b'),
                        artifact("container", ExperimentArtifactKind.CONTAINER, 'c'),
                        artifact("config", ExperimentArtifactKind.CONFIG, 'd'),
                        artifact("dependencies", ExperimentArtifactKind.DEPENDENCY_LOCK, 'f'),
                        artifact("model", ExperimentArtifactKind.MODEL, "model-none-v1", '1'),
                        artifact("prompt", ExperimentArtifactKind.PROMPT, "prompt-none-v1", '2')),
                List.of(
                        new ExperimentTrialDefinition("trial-a", 0, "candidate", 11L,
                                "e".repeat(64)),
                        new ExperimentTrialDefinition("trial-b", 1, "negative control", 22L,
                                "e".repeat(64))));
    }

    private ExperimentArtifactReference artifact(String id, ExperimentArtifactKind kind,
                                                  char digest) {
        return artifact(id, kind, "v1", digest);
    }

    private ExperimentArtifactReference artifact(String id, ExperimentArtifactKind kind,
                                                  String version, char digest) {
        return new ExperimentArtifactReference(id, kind, version, "immutable://" + id,
                String.valueOf(digest).repeat(64), "application/octet-stream");
    }

    private ExperimentReproductionInputs inputs(String registrationSha, long seed) {
        return new ExperimentReproductionInputs(registrationSha, artifactDigests(), seed,
                "e".repeat(64), "model-none-v1", "prompt-none-v1");
    }

    private Map<String, String> artifactDigests() {
        return Map.of("dataset", "a".repeat(64), "code", "b".repeat(64),
                "container", "c".repeat(64), "config", "d".repeat(64),
                "dependencies", "f".repeat(64), "model", "1".repeat(64),
                "prompt", "2".repeat(64));
    }

    private ExperimentCertificationEvidencePort certifiedEvidencePort() {
        return (registrationId, registrationSha256) -> new ExperimentCertificationEvidence(
                registrationId, registrationSha256, ExperimentCertificationVerdict.PASS,
                "immutable://certification", "v1", "3".repeat(64), "4".repeat(64),
                "research-certifier-key", "Ed25519", "signed-value", true,
                true, true, true, true, true, System.currentTimeMillis() - 1_000,
                System.currentTimeMillis() + 60_000, List.of());
    }

    private ExperimentResultArtifact result(char digest) {
        return new ExperimentResultArtifact("immutable://result", "v1",
                String.valueOf(digest).repeat(64));
    }

    private static final class InMemoryMapper implements ExperimentRegistryMapper {
        private final Map<String, ExperimentRegistrationDO> registrations = new LinkedHashMap<>();
        private final List<ExperimentArtifactDO> artifacts = new ArrayList<>();
        private final List<ExperimentTrialDO> trials = new ArrayList<>();
        private final List<ExperimentEventDO> events = new ArrayList<>();
        private final Map<String, ExperimentChainHeadDO> chainHeads = new LinkedHashMap<>();

        @Override
        public int insertRegistration(ExperimentRegistrationDO registration) {
            boolean duplicate = registrations.values().stream().anyMatch(existing ->
                    existing.getIdempotencyKey().equals(registration.getIdempotencyKey())
                            || existing.getRegistrationSha256().equals(
                            registration.getRegistrationSha256()));
            if (duplicate) return 0;
            registrations.put(registration.getRegistrationId(), registration);
            return 1;
        }

        @Override
        public int insertChainHead(ExperimentChainHeadDO chainHead) {
            chainHeads.put(chainHead.getRegistrationId(), chainHead);
            return 1;
        }

        @Override
        public int insertArtifact(ExperimentArtifactDO artifact) {
            artifacts.add(artifact);
            return 1;
        }

        @Override
        public int insertTrial(ExperimentTrialDO trial) {
            trials.add(trial);
            return 1;
        }

        @Override
        public int insertEvent(ExperimentEventDO event) {
            events.add(event);
            return 1;
        }

        @Override
        public int compareAndSetChainHead(String registrationId, long expectedSequence,
                                         String expectedSha256, long expectedVersion,
                                         long nextSequence, String nextSha256) {
            ExperimentChainHeadDO head = chainHeads.get(registrationId);
            if (head == null || head.getLastEventSequence() != expectedSequence
                    || !head.getLastEventSha256().equals(expectedSha256)
                    || head.getVersion() != expectedVersion) return 0;
            head.setLastEventSequence(nextSequence);
            head.setLastEventSha256(nextSha256);
            head.setVersion(expectedVersion + 1);
            return 1;
        }

        @Override
        public ExperimentRegistrationDO selectRegistration(String registrationId) {
            return registrations.get(registrationId);
        }

        @Override
        public ExperimentRegistrationDO selectRegistrationForUpdate(String registrationId) {
            return selectRegistration(registrationId);
        }

        @Override
        public ExperimentRegistrationDO selectRegistrationByIdempotencyKeyForUpdate(String key) {
            return registrations.values().stream()
                    .filter(row -> row.getIdempotencyKey().equals(key)).findFirst().orElse(null);
        }

        @Override
        public ExperimentRegistrationDO selectRegistrationBySha256ForUpdate(String sha256) {
            return registrations.values().stream()
                    .filter(row -> row.getRegistrationSha256().equals(sha256))
                    .findFirst().orElse(null);
        }

        @Override
        public ExperimentChainHeadDO selectChainHead(String registrationId) {
            return chainHeads.get(registrationId);
        }

        @Override
        public ExperimentChainHeadDO selectChainHeadForUpdate(String registrationId) {
            return selectChainHead(registrationId);
        }

        @Override
        public List<ExperimentRegistrationDO> selectRecentRegistrations(int limit) {
            return registrations.values().stream()
                    .sorted(Comparator.comparingLong(ExperimentRegistrationDO::getRegisteredAtMs)
                            .reversed()).limit(limit).toList();
        }

        @Override
        public List<ExperimentArtifactDO> selectArtifacts(String registrationId) {
            return artifacts.stream().filter(row -> row.getRegistrationId().equals(registrationId))
                    .sorted(Comparator.comparing(ExperimentArtifactDO::getArtifactId)).toList();
        }

        @Override
        public List<ExperimentTrialDO> selectTrials(String registrationId) {
            return trials.stream().filter(row -> row.getRegistrationId().equals(registrationId))
                    .sorted(Comparator.comparingInt(ExperimentTrialDO::getTrialOrdinal)).toList();
        }

        @Override
        public List<ExperimentEventDO> selectEvents(String registrationId) {
            return events.stream().filter(row -> row.getRegistrationId().equals(registrationId))
                    .sorted(Comparator.comparingLong(ExperimentEventDO::getEventSequence)).toList();
        }

        @Override
        public ExperimentEventDO selectLatestTrialEvent(String registrationId, String trialId) {
            return events.stream().filter(row -> row.getRegistrationId().equals(registrationId)
                            && trialId.equals(row.getTrialId()))
                    .max(Comparator.comparingLong(ExperimentEventDO::getEventSequence)).orElse(null);
        }

        @Override
        public ExperimentEventDO selectEventByCommandKey(String registrationId, String key) {
            return events.stream().filter(row -> row.getRegistrationId().equals(registrationId)
                            && key.equals(row.getCommandIdempotencyKey()))
                    .findFirst().orElse(null);
        }

        @Override
        public ExperimentEventDO selectLastEvent(String registrationId) {
            return events.stream().filter(row -> row.getRegistrationId().equals(registrationId))
                    .max(Comparator.comparingLong(ExperimentEventDO::getEventSequence)).orElse(null);
        }
    }
}
