package com.tj.crypto.research.experiment;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Append-only command/query service for pre-registered research experiment families.
 * It intentionally exposes no update or delete operation.
 */
@Service
public class ExperimentRegistryService {
    private final ExperimentRegistryMapper mapper;
    private final ExperimentCertificationEvidencePort certificationEvidencePort;

    public ExperimentRegistryService(ExperimentRegistryMapper mapper) {
        this(mapper, (registrationId, registrationSha256) ->
                ExperimentCertificationEvidence.notVerified(registrationId,
                        registrationSha256, "CERTIFICATION_NOT_VERIFIED"));
    }

    public ExperimentRegistryService(ExperimentRegistryMapper mapper,
                                     ExperimentCertificationEvidencePort certificationEvidencePort) {
        this.mapper = mapper;
        this.certificationEvidencePort = Objects.requireNonNull(certificationEvidencePort,
                "certificationEvidencePort is required");
    }

    @Autowired
    public ExperimentRegistryService(
            ExperimentRegistryMapper mapper,
            ObjectProvider<ExperimentCertificationEvidencePort> certificationEvidencePorts) {
        this(mapper, certificationEvidencePorts.getIfUnique(() ->
                (registrationId, registrationSha256) ->
                        ExperimentCertificationEvidence.notVerified(registrationId,
                                registrationSha256, "CERTIFICATION_NOT_VERIFIED")));
    }

    /**
     * Idempotently freezes a complete family. The idempotency key may only ever name
     * one registration digest; retries of identical content return the original row.
     */
    @Transactional
    public ExperimentRegistrationReceipt register(ExperimentFamilyRegistration command,
                                                  String operator) {
        Objects.requireNonNull(command, "command is required");
        String recordedBy = operator(operator);
        String registrationSha256 = ExperimentDigest.registration(command);
        long now = System.currentTimeMillis();

        ExperimentRegistrationDO registration = new ExperimentRegistrationDO();
        registration.setRegistrationId("exp-" + UUID.randomUUID());
        registration.setIdempotencyKey(command.idempotencyKey());
        registration.setRegistrationSha256(registrationSha256);
        registration.setExperimentName(command.experimentName());
        registration.setHypothesis(command.hypothesis());
        registration.setProtocolVersion(command.protocolVersion());
        registration.setModelVersion(command.modelVersion());
        registration.setPromptVersion(command.promptVersion());
        registration.setDeclaredTrialCount(command.declaredTrialCount());
        registration.setRegisteredBy(recordedBy);
        registration.setRegisteredAtMs(now);

        try {
            if (mapper.insertRegistration(registration) != 1) {
                return resolveRegistrationConflict(command, registrationSha256);
            }
        } catch (DuplicateKeyException duplicateRegistration) {
            return resolveRegistrationConflict(command, registrationSha256);
        }

        ExperimentChainHeadDO chainHead = new ExperimentChainHeadDO();
        chainHead.setRegistrationId(registration.getRegistrationId());
        chainHead.setLastEventSequence(-1L);
        chainHead.setLastEventSha256(ExperimentDigest.GENESIS_SHA256);
        chainHead.setVersion(0L);
        requireInserted(mapper.insertChainHead(chainHead), "experiment chain head");

        for (ExperimentArtifactReference artifact : command.artifacts()) {
            requireInserted(mapper.insertArtifact(toData(registration.getRegistrationId(), artifact)),
                    "artifact " + artifact.artifactId());
        }
        for (ExperimentTrialDefinition trial : command.trials()) {
            requireInserted(mapper.insertTrial(toData(registration.getRegistrationId(), trial)),
                    "trial " + trial.trialId());
        }

        appendNewEvent(registration.getRegistrationId(), null,
                "family:registered",
                "FAMILY_REGISTERED", null, ExperimentState.REGISTERED, null,
                "Complete family of " + command.declaredTrialCount() + " trials pre-registered",
                null, null, recordedBy, now);

        for (ExperimentTrialDefinition trial : command.trials()) {
            appendNewEvent(registration.getRegistrationId(),
                    trial.trialId(), "trial:" + trial.trialId() + ":registered",
                    "TRIAL_REGISTERED", null, ExperimentState.REGISTERED,
                    null, "Trial frozen before execution", null, null, recordedBy, now);
        }
        return receipt(registration, false);
    }

    /** Check exact registered inputs before dispatching any trial work. */
    @Transactional(readOnly = true)
    public ExperimentReproducibilityReport checkReproducibility(
            String registrationId, String trialId, ExperimentReproductionInputs observed) {
        ExperimentRegistrationDO registration = requireRegistration(registrationId);
        ExperimentAuditTrail integrity = audit(registration);
        ExperimentTrialDO trial = requireTrial(registrationId, trialId,
                mapper.selectTrials(registrationId));
        return checkReproducibility(registration, trial, mapper.selectArtifacts(registrationId),
                Objects.requireNonNull(observed, "observed inputs are required"), integrity);
    }

    /** REGISTERED -> RUNNING, allowed only when all observed provenance matches exactly. */
    @Transactional
    public ExperimentEventAudit startTrial(String registrationId, String trialId,
                                           ExperimentReproductionInputs observed,
                                           String operator) {
        return startTrial(registrationId, trialId, observed,
                "trial:" + ExperimentValidation.required(trialId, "trialId", 100) + ":start",
                operator);
    }

    @Transactional
    public ExperimentEventAudit startTrial(String registrationId, String trialId,
                                           ExperimentReproductionInputs observed,
                                           String commandIdempotencyKey, String operator) {
        ExperimentRegistrationDO registration = requireLockedRegistration(registrationId);
        ExperimentAuditTrail integrity = requireValidFrozenFamily(registration);
        ExperimentTrialDO trial = requireTrial(registrationId, trialId,
                mapper.selectTrials(registrationId));
        ExperimentReproducibilityReport report = checkReproducibility(registration, trial,
                mapper.selectArtifacts(registrationId),
                Objects.requireNonNull(observed, "observed inputs are required"), integrity);
        if (!report.reproducible()) {
            throw new ExperimentReproducibilityException(report);
        }
        return appendTransition(registrationId, trialId, ExperimentState.RUNNING,
                "TRIAL_STARTED", null, "Registered inputs reproduced exactly",
                report.observedProvenanceSha256(), null,
                ExperimentValidation.required(commandIdempotencyKey,
                        "commandIdempotencyKey", 128), operator(operator));
    }

    /**
     * Append a terminal outcome. Failures and cancellations are first-class events and
     * may be recorded even when worker setup failed before RUNNING.
     */
    @Transactional
    public ExperimentEventAudit recordOutcome(String registrationId, String trialId,
                                              ExperimentState outcome, String reasonCode,
                                              String detailMessage,
                                              ExperimentResultArtifact resultArtifact,
                                              String operator) {
        return recordOutcome(registrationId, trialId, outcome, reasonCode, detailMessage,
                resultArtifact,
                "trial:" + ExperimentValidation.required(trialId, "trialId", 100) + ":terminal",
                operator);
    }

    @Transactional
    public ExperimentEventAudit recordOutcome(String registrationId, String trialId,
                                              ExperimentState outcome, String reasonCode,
                                              String detailMessage,
                                              ExperimentResultArtifact resultArtifact,
                                              String commandIdempotencyKey, String operator) {
        ExperimentRegistrationDO registration = requireLockedRegistration(registrationId);
        requireValidFrozenFamily(registration);
        if (outcome == null || !outcome.terminal()) {
            throw new IllegalArgumentException("outcome must be SUCCEEDED, FAILED or CANCELLED");
        }
        if (outcome == ExperimentState.SUCCEEDED && resultArtifact == null) {
            throw new IllegalArgumentException("a successful trial requires a SHA-256 result artifact");
        }
        String normalizedReason = reasonCode == null ? null
                : ExperimentValidation.required(reasonCode, "reasonCode", 100);
        if (outcome == ExperimentState.SUCCEEDED && normalizedReason != null) {
            throw new IllegalArgumentException("successful trials must not carry a failure reasonCode");
        }
        if (outcome != ExperimentState.SUCCEEDED && normalizedReason == null) {
            throw new IllegalArgumentException("failed and cancelled trials require a reasonCode");
        }
        String normalizedDetail = detailMessage == null ? null
                : ExperimentValidation.required(detailMessage, "detailMessage", 2000);
        return appendTransition(registrationId, trialId, outcome, "TRIAL_" + outcome,
                normalizedReason, normalizedDetail, null, resultArtifact,
                ExperimentValidation.required(commandIdempotencyKey,
                        "commandIdempotencyKey", 128), operator(operator));
    }

    @Transactional(readOnly = true)
    public ExperimentAuditTrail audit(String registrationId) {
        ExperimentRegistrationDO registration = requireRegistration(registrationId);
        return audit(registration);
    }

    @Transactional(readOnly = true)
    public List<ExperimentAuditTrail> recentAudit(int limit) {
        return mapper.selectRecentRegistrations(Math.max(1, Math.min(100, limit))).stream()
                .map(this::audit)
                .toList();
    }

    /** Deterministic fail-closed projection for strategy promotion policy. */
    @Transactional(readOnly = true)
    public ExperimentPromotionEvidence promotionEvidence(String registrationId,
                                                         String expectedRegistrationSha256) {
        List<String> reasons = new ArrayList<>();
        if (registrationId == null || registrationId.isBlank()) {
            return ineligible(registrationId, expectedRegistrationSha256,
                    "research registration id is missing");
        }
        ExperimentRegistrationDO registration = mapper.selectRegistration(registrationId.trim());
        if (registration == null) {
            return ineligible(registrationId.trim(), expectedRegistrationSha256,
                    "research registration does not exist");
        }
        String expected;
        try {
            expected = ExperimentValidation.sha256(expectedRegistrationSha256,
                    "expected registration sha256");
        } catch (IllegalArgumentException e) {
            return ineligible(registration.getRegistrationId(), registration.getRegistrationSha256(),
                    e.getMessage());
        }
        if (!registration.getRegistrationSha256().equals(expected)) {
            reasons.add("research registration SHA-256 does not match signed evidence");
        }
        ExperimentAuditTrail audit;
        try {
            audit = audit(registration);
        } catch (RuntimeException malformedEvidence) {
            return ineligible(registration.getRegistrationId(), registration.getRegistrationSha256(),
                    "experiment registry evidence cannot be parsed or verified");
        }
        boolean allTerminal = audit.completeTrialFamily()
                && audit.trialStates().values().stream().allMatch(ExperimentState::terminal);
        boolean allSucceeded = audit.completeTrialFamily()
                && audit.trialStates().values().stream()
                .allMatch(state -> state == ExperimentState.SUCCEEDED);
        if (!audit.completeTrialFamily()) reasons.add("pre-registered trial family is incomplete");
        if (!allTerminal) reasons.add("not every pre-registered trial has a recorded terminal outcome");
        if (!allSucceeded) reasons.add("not every pre-registered trial completed successfully");
        if (!audit.auditChainValid()) reasons.add("experiment audit hash chain is invalid");

        ExperimentCertificationEvidence certification = loadCertification(registration);
        if (!certification.certifiedAt(System.currentTimeMillis())) {
            reasons.add("CERTIFICATION_NOT_VERIFIED: signed OOS/cost/capacity/DSR/PBO PASS evidence is missing, invalid or expired");
            reasons.addAll(certification.reasons());
        }
        return new ExperimentPromotionEvidence(registration.getRegistrationId(),
                registration.getRegistrationSha256(), audit.completeTrialFamily(), allTerminal,
                allSucceeded, audit.auditChainValid(), certification, reasons.isEmpty(), reasons);
    }

    private ExperimentEventAudit appendTransition(
            String registrationId, String trialId, ExperimentState target, String eventType,
            String reasonCode, String detailMessage, String observedProvenanceSha256,
            ExperimentResultArtifact resultArtifact, String commandIdempotencyKey,
            String recordedBy) {
        String commandSha256 = ExperimentDigest.command(registrationId, trialId,
                commandIdempotencyKey, eventType, target, reasonCode, detailMessage,
                observedProvenanceSha256, resultArtifact, recordedBy);
        ExperimentEventDO existingCommand = mapper.selectEventByCommandKey(registrationId,
                commandIdempotencyKey);
        if (existingCommand != null) {
            if (!commandSha256.equals(existingCommand.getCommandSha256())) {
                throw new ExperimentRegistrationConflictException(
                        "Experiment command idempotency key already names different immutable content: "
                                + commandIdempotencyKey);
            }
            return toAudit(existingCommand);
        }

        ExperimentEventDO latest = mapper.selectLatestTrialEvent(registrationId, trialId);
        if (latest == null) throw new IllegalArgumentException("Unknown trial: " + trialId);
        ExperimentState current = ExperimentState.valueOf(latest.getToStatus());
        if (!current.mayTransitionTo(target)) {
            throw new IllegalStateException("Illegal experiment transition " + current + " -> "
                    + target + " for trial " + trialId);
        }
        long now = System.currentTimeMillis();
        ExperimentEventDO next = appendNewEvent(registrationId, trialId,
                commandIdempotencyKey, eventType, current, target, reasonCode, detailMessage,
                observedProvenanceSha256, resultArtifact, recordedBy, now);
        return toAudit(next);
    }

    private ExperimentEventDO appendNewEvent(
            String registrationId, String trialId, String commandIdempotencyKey,
            String eventType, ExperimentState from, ExperimentState to, String reasonCode,
            String detailMessage, String observedProvenanceSha256,
            ExperimentResultArtifact resultArtifact, String recordedBy, long occurredAt) {
        String normalizedCommandKey = ExperimentValidation.required(commandIdempotencyKey,
                "commandIdempotencyKey", 128);
        String commandSha256 = ExperimentDigest.command(registrationId, trialId,
                normalizedCommandKey, eventType, to, reasonCode, detailMessage,
                observedProvenanceSha256, resultArtifact, recordedBy);
        ExperimentEventDO existing = mapper.selectEventByCommandKey(registrationId,
                normalizedCommandKey);
        if (existing != null) {
            if (!commandSha256.equals(existing.getCommandSha256())) {
                throw new ExperimentRegistrationConflictException(
                        "Experiment command idempotency key already names different immutable content: "
                                + normalizedCommandKey);
            }
            return existing;
        }

        ExperimentChainHeadDO head = mapper.selectChainHeadForUpdate(registrationId);
        if (head == null) throw new IllegalStateException("Experiment audit chain head is missing");
        ExperimentEventDO last = mapper.selectLastEvent(registrationId);
        validateLockedChainHead(head, last);

        long nextSequence = head.getLastEventSequence() + 1;
        ExperimentEventDO next = event(registrationId, trialId, normalizedCommandKey,
                commandSha256, nextSequence, eventType, from, to, reasonCode, detailMessage,
                observedProvenanceSha256, resultArtifact, recordedBy, occurredAt,
                head.getLastEventSha256());
        requireInserted(mapper.insertEvent(next), "experiment lifecycle event");
        int advanced = mapper.compareAndSetChainHead(registrationId,
                head.getLastEventSequence(), head.getLastEventSha256(), head.getVersion(),
                nextSequence, next.getEventSha256());
        if (advanced != 1) {
            throw new IllegalStateException("Experiment audit chain compare-and-swap failed");
        }
        return next;
    }

    private void validateLockedChainHead(ExperimentChainHeadDO head, ExperimentEventDO last) {
        if (head.getLastEventSequence() == null || head.getVersion() == null
                || head.getLastEventSha256() == null) {
            throw new IllegalStateException("Experiment audit chain head is malformed");
        }
        if (head.getLastEventSequence() == -1L) {
            if (last != null || head.getVersion() != 0L
                    || !ExperimentDigest.GENESIS_SHA256.equals(head.getLastEventSha256())) {
                throw new IllegalStateException("Experiment genesis chain head does not match event history");
            }
            return;
        }
        if (last == null || last.getEventSequence() == null
                || last.getEventSequence().longValue() != head.getLastEventSequence()
                || !head.getLastEventSha256().equals(last.getEventSha256())
                || !ExperimentDigest.event(last).equals(last.getEventSha256())
                || head.getVersion() != head.getLastEventSequence() + 1) {
            throw new IllegalStateException("Experiment chain head does not match immutable event history");
        }
    }

    private ExperimentRegistrationReceipt resolveRegistrationConflict(
            ExperimentFamilyRegistration command, String registrationSha256) {
        ExperimentRegistrationDO existing = mapper.selectRegistrationByIdempotencyKeyForUpdate(
                command.idempotencyKey());
        if (existing != null && existing.getRegistrationSha256().equals(registrationSha256)) {
            ExperimentAuditTrail existingAudit = audit(existing);
            if (!existingAudit.completeTrialFamily() || !existingAudit.auditChainValid()) {
                throw new ExperimentRegistrationConflictException(
                        "Existing idempotent registration is incomplete or has invalid audit evidence");
            }
            return receipt(existing, true);
        }
        ExperimentRegistrationDO duplicateDigest = mapper.selectRegistrationBySha256ForUpdate(
                registrationSha256);
        if (duplicateDigest != null) {
            throw new ExperimentRegistrationConflictException(
                    "Identical immutable registration already exists under idempotency key "
                            + duplicateDigest.getIdempotencyKey());
        }
        throw new ExperimentRegistrationConflictException(
                "Idempotency key already names different immutable experiment content: "
                        + command.idempotencyKey());
    }

    private ExperimentAuditTrail requireValidFrozenFamily(ExperimentRegistrationDO registration) {
        ExperimentAuditTrail audit;
        try {
            audit = audit(registration);
        } catch (RuntimeException malformedEvidence) {
            throw new IllegalStateException(
                    "Experiment registry evidence cannot be parsed or verified", malformedEvidence);
        }
        if (!audit.completeTrialFamily() || !audit.auditChainValid()) {
            throw new IllegalStateException(
                    "Experiment trial family is incomplete or its audit chain is invalid");
        }
        return audit;
    }

    private ExperimentCertificationEvidence loadCertification(
            ExperimentRegistrationDO registration) {
        try {
            ExperimentCertificationEvidence evidence = certificationEvidencePort.load(
                    registration.getRegistrationId(), registration.getRegistrationSha256());
            if (evidence == null
                    || !registration.getRegistrationId().equals(evidence.registrationId())
                    || !registration.getRegistrationSha256().equals(evidence.registrationSha256())) {
                return ExperimentCertificationEvidence.notVerified(
                        registration.getRegistrationId(), registration.getRegistrationSha256(),
                        "CERTIFICATION_IDENTITY_MISMATCH");
            }
            return evidence;
        } catch (RuntimeException invalidOrUnavailableCertification) {
            return ExperimentCertificationEvidence.notVerified(registration.getRegistrationId(),
                    registration.getRegistrationSha256(),
                    "CERTIFICATION_ADAPTER_UNAVAILABLE_OR_INVALID");
        }
    }

    private ExperimentReproducibilityReport checkReproducibility(
            ExperimentRegistrationDO registration, ExperimentTrialDO trial,
            List<ExperimentArtifactDO> artifacts, ExperimentReproductionInputs observed,
            ExperimentAuditTrail integrity) {
        List<String> mismatches = new ArrayList<>();
        if (!integrity.completeTrialFamily()) mismatches.add("complete pre-registered trial family");
        if (!integrity.auditChainValid()) mismatches.add("registration and audit hash-chain integrity");
        if (!registration.getRegistrationSha256().equals(observed.registrationSha256())) {
            mismatches.add("registration SHA-256");
        }
        Map<String, String> expectedArtifacts = new TreeMap<>();
        for (ExperimentArtifactDO artifact : artifacts) {
            expectedArtifacts.put(artifact.getArtifactId(), artifact.getArtifactSha256());
        }
        if (!expectedArtifacts.equals(new TreeMap<>(observed.artifactSha256ById()))) {
            Set<String> missing = new HashSet<>(expectedArtifacts.keySet());
            missing.removeAll(observed.artifactSha256ById().keySet());
            Set<String> extra = new HashSet<>(observed.artifactSha256ById().keySet());
            extra.removeAll(expectedArtifacts.keySet());
            if (!missing.isEmpty()) mismatches.add("missing artifacts " + missing);
            if (!extra.isEmpty()) mismatches.add("unregistered artifacts " + extra);
            expectedArtifacts.forEach((id, sha) -> {
                if (observed.artifactSha256ById().containsKey(id)
                        && !sha.equals(observed.artifactSha256ById().get(id))) {
                    mismatches.add("artifact SHA-256 " + id);
                }
            });
        }
        if (trial.getRandomSeed() != observed.randomSeed()) mismatches.add("random seed");
        if (!trial.getParameterSha256().equals(observed.parameterSha256())) {
            mismatches.add("parameter SHA-256");
        }
        if (!registration.getModelVersion().equals(observed.modelVersion())) {
            mismatches.add("model version");
        }
        if (!registration.getPromptVersion().equals(observed.promptVersion())) {
            mismatches.add("prompt version");
        }
        String observedDigest = ExperimentDigest.provenance(observed);
        return new ExperimentReproducibilityReport(mismatches.isEmpty(), mismatches,
                registration.getRegistrationSha256(), observedDigest);
    }

    private ExperimentAuditTrail audit(ExperimentRegistrationDO registration) {
        String registrationId = registration.getRegistrationId();
        List<ExperimentArtifactDO> artifactRows = mapper.selectArtifacts(registrationId);
        List<ExperimentTrialDO> trialRows = mapper.selectTrials(registrationId);
        List<ExperimentEventDO> eventRows = mapper.selectEvents(registrationId);
        ExperimentChainHeadDO chainHead = mapper.selectChainHead(registrationId);

        List<ExperimentArtifactReference> artifacts = artifactRows.stream()
                .map(row -> new ExperimentArtifactReference(row.getArtifactId(),
                        ExperimentArtifactKind.valueOf(row.getArtifactKind()),
                        row.getArtifactVersion(), row.getArtifactUri(), row.getArtifactSha256(),
                        row.getMediaType()))
                .toList();
        List<ExperimentTrialDefinition> trials = trialRows.stream()
                .map(row -> new ExperimentTrialDefinition(row.getTrialId(), row.getTrialOrdinal(),
                        row.getHypothesis(), row.getRandomSeed(), row.getParameterSha256()))
                .toList();
        List<ExperimentEventAudit> events = eventRows.stream().map(this::toAudit).toList();

        Map<String, ExperimentState> states = new LinkedHashMap<>();
        trials.forEach(trial -> states.put(trial.trialId(), ExperimentState.REGISTERED));
        eventRows.stream().filter(event -> event.getTrialId() != null)
                .forEach(event -> states.put(event.getTrialId(),
                        ExperimentState.valueOf(event.getToStatus())));

        boolean complete = completeFamily(registration, artifacts, trials);
        boolean chainValid = registrationDigestValid(registration, artifacts, trials)
                && validateAuditChain(registration, eventRows, trials, artifacts, chainHead);
        return new ExperimentAuditTrail(snapshot(registration), artifacts, trials, events, states,
                familyState(states), chainHead == null ? -2L : chainHead.getLastEventSequence(),
                chainHead == null ? null : chainHead.getLastEventSha256(), complete, chainValid);
    }

    private boolean completeFamily(ExperimentRegistrationDO registration,
                                   List<ExperimentArtifactReference> artifacts,
                                   List<ExperimentTrialDefinition> trials) {
        if (registration.getDeclaredTrialCount() != trials.size()) return false;
        Set<Integer> ordinals = new HashSet<>();
        trials.forEach(trial -> ordinals.add(trial.ordinal()));
        for (int i = 0; i < trials.size(); i++) if (!ordinals.contains(i)) return false;
        Map<ExperimentArtifactKind, Integer> counts = new EnumMap<>(ExperimentArtifactKind.class);
        artifacts.forEach(artifact -> counts.merge(artifact.kind(), 1, Integer::sum));
        return counts.getOrDefault(ExperimentArtifactKind.DATASET, 0) >= 1
                && counts.getOrDefault(ExperimentArtifactKind.CODE, 0) == 1
                && counts.getOrDefault(ExperimentArtifactKind.CONTAINER, 0) == 1
                && counts.getOrDefault(ExperimentArtifactKind.CONFIG, 0) == 1
                && counts.getOrDefault(ExperimentArtifactKind.DEPENDENCY_LOCK, 0) == 1
                && counts.getOrDefault(ExperimentArtifactKind.MODEL, 0) == 1
                && counts.getOrDefault(ExperimentArtifactKind.PROMPT, 0) == 1;
    }

    private boolean registrationDigestValid(ExperimentRegistrationDO registration,
                                            List<ExperimentArtifactReference> artifacts,
                                            List<ExperimentTrialDefinition> trials) {
        try {
            ExperimentFamilyRegistration reconstructed = new ExperimentFamilyRegistration(
                    registration.getIdempotencyKey(), registration.getExperimentName(),
                    registration.getHypothesis(), registration.getProtocolVersion(),
                    registration.getModelVersion(), registration.getPromptVersion(),
                    registration.getDeclaredTrialCount(), artifacts, trials);
            return registration.getRegistrationSha256().equals(
                    ExperimentDigest.registration(reconstructed));
        } catch (RuntimeException malformedEvidence) {
            return false;
        }
    }

    private boolean validateAuditChain(ExperimentRegistrationDO registration,
                                       List<ExperimentEventDO> events,
                                       List<ExperimentTrialDefinition> trials,
                                       List<ExperimentArtifactReference> artifacts,
                                       ExperimentChainHeadDO chainHead) {
        if (events.size() < trials.size() + 1 || chainHead == null) return false;
        try {
            String previous = ExperimentDigest.GENESIS_SHA256;
            for (int index = 0; index < events.size(); index++) {
                ExperimentEventDO event = events.get(index);
                if (event.getEventSequence() == null || event.getEventSequence() != index
                        || !previous.equals(event.getPreviousEventSha256())
                        || event.getCommandIdempotencyKey() == null
                        || event.getCommandSha256() == null
                        || !ExperimentDigest.event(event).equals(event.getEventSha256())) {
                    return false;
                }
                ExperimentResultArtifact result = event.getResultArtifactSha256() == null ? null
                        : new ExperimentResultArtifact(event.getResultArtifactUri(),
                        event.getResultArtifactVersion(), event.getResultArtifactSha256());
                String expectedCommandSha = ExperimentDigest.command(
                        event.getRegistrationId(), event.getTrialId(),
                        event.getCommandIdempotencyKey(), event.getEventType(),
                        ExperimentState.valueOf(event.getToStatus()), event.getReasonCode(),
                        event.getDetailMessage(), event.getObservedProvenanceSha256(), result,
                        event.getRecordedBy());
                if (!expectedCommandSha.equals(event.getCommandSha256())) return false;
                previous = event.getEventSha256();
            }
            ExperimentEventDO tail = events.get(events.size() - 1);
            if (chainHead.getLastEventSequence().longValue()
                    != tail.getEventSequence().longValue()
                    || !chainHead.getLastEventSha256().equals(tail.getEventSha256())
                    || chainHead.getVersion().longValue() != events.size()) {
                return false;
            }

            ExperimentEventDO family = events.get(0);
            if (family.getTrialId() != null || family.getFromStatus() != null
                    || !"family:registered".equals(family.getCommandIdempotencyKey())
                    || !"FAMILY_REGISTERED".equals(family.getEventType())
                    || !ExperimentState.REGISTERED.name().equals(family.getToStatus())
                    || family.getReasonCode() != null
                    || family.getObservedProvenanceSha256() != null
                    || family.getResultArtifactSha256() != null) {
                return false;
            }

            Map<String, ExperimentState> states = new HashMap<>();
            Map<String, ExperimentTrialDefinition> trialById = new HashMap<>();
            for (int index = 0; index < trials.size(); index++) {
                ExperimentTrialDefinition trial = trials.get(index);
                ExperimentEventDO declaration = events.get(index + 1);
                if (!trial.trialId().equals(declaration.getTrialId())
                        || declaration.getFromStatus() != null
                        || !("trial:" + trial.trialId() + ":registered")
                        .equals(declaration.getCommandIdempotencyKey())
                        || !"TRIAL_REGISTERED".equals(declaration.getEventType())
                        || !ExperimentState.REGISTERED.name().equals(declaration.getToStatus())
                        || declaration.getReasonCode() != null
                        || declaration.getObservedProvenanceSha256() != null
                        || declaration.getResultArtifactSha256() != null) {
                    return false;
                }
                states.put(trial.trialId(), ExperimentState.REGISTERED);
                trialById.put(trial.trialId(), trial);
            }

            Map<String, String> artifactDigests = new TreeMap<>();
            artifacts.forEach(artifact -> artifactDigests.put(artifact.artifactId(),
                    artifact.sha256()));
            for (int index = trials.size() + 1; index < events.size(); index++) {
                ExperimentEventDO event = events.get(index);
                ExperimentTrialDefinition trial = trialById.get(event.getTrialId());
                if (trial == null) return false;
                ExperimentState prior = states.get(trial.trialId());
                ExperimentState next = ExperimentState.valueOf(event.getToStatus());
                String expectedEventType = next == ExperimentState.RUNNING
                        ? "TRIAL_STARTED" : "TRIAL_" + next;
                if (!prior.name().equals(event.getFromStatus()) || !prior.mayTransitionTo(next)
                        || !expectedEventType.equals(event.getEventType())) {
                    return false;
                }
                if (next == ExperimentState.RUNNING) {
                    ExperimentReproductionInputs expectedInputs = new ExperimentReproductionInputs(
                            registration.getRegistrationSha256(), artifactDigests,
                            trial.randomSeed(), trial.parameterSha256(),
                            registration.getModelVersion(), registration.getPromptVersion());
                    if (!ExperimentDigest.provenance(expectedInputs)
                            .equals(event.getObservedProvenanceSha256())) return false;
                    if (event.getReasonCode() != null || event.getResultArtifactSha256() != null) {
                        return false;
                    }
                }
                if (next == ExperimentState.SUCCEEDED
                        && (event.getResultArtifactSha256() == null
                        || event.getReasonCode() != null
                        || event.getObservedProvenanceSha256() != null)) return false;
                if ((next == ExperimentState.FAILED || next == ExperimentState.CANCELLED)
                        && (event.getReasonCode() == null || event.getReasonCode().isBlank())) {
                    return false;
                }
                if ((next == ExperimentState.FAILED || next == ExperimentState.CANCELLED)
                        && event.getObservedProvenanceSha256() != null) return false;
                states.put(trial.trialId(), next);
            }
            return states.keySet().equals(trialById.keySet());
        } catch (RuntimeException malformedEvidence) {
            return false;
        }
    }

    private ExperimentFamilyState familyState(Map<String, ExperimentState> states) {
        if (states.isEmpty() || states.values().stream()
                .allMatch(state -> state == ExperimentState.REGISTERED)) {
            return ExperimentFamilyState.REGISTERED;
        }
        if (states.values().stream().allMatch(state -> state == ExperimentState.SUCCEEDED)) {
            return ExperimentFamilyState.SUCCEEDED;
        }
        if (states.values().stream().allMatch(ExperimentState::terminal)) {
            return ExperimentFamilyState.FAILED;
        }
        return ExperimentFamilyState.RUNNING;
    }

    private ExperimentRegistrationDO requireRegistration(String registrationId) {
        String normalized = ExperimentValidation.required(registrationId, "registrationId", 64);
        ExperimentRegistrationDO registration = mapper.selectRegistration(normalized);
        if (registration == null) throw new IllegalArgumentException("Unknown experiment: " + normalized);
        return registration;
    }

    private ExperimentRegistrationDO requireLockedRegistration(String registrationId) {
        String normalized = ExperimentValidation.required(registrationId, "registrationId", 64);
        ExperimentRegistrationDO registration = mapper.selectRegistrationForUpdate(normalized);
        if (registration == null) throw new IllegalArgumentException("Unknown experiment: " + normalized);
        return registration;
    }

    private ExperimentTrialDO requireTrial(String registrationId, String trialId,
                                           List<ExperimentTrialDO> trials) {
        String normalized = ExperimentValidation.required(trialId, "trialId", 100);
        return trials.stream().filter(trial -> trial.getTrialId().equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown trial " + normalized + " in experiment " + registrationId));
    }

    private ExperimentEventDO event(
            String registrationId, String trialId, String commandIdempotencyKey,
            String commandSha256, long sequence, String type,
            ExperimentState from, ExperimentState to, String reasonCode, String detail,
            String provenanceSha256, ExperimentResultArtifact result, String recordedBy,
            long occurredAt, String previousHash) {
        ExperimentEventDO event = new ExperimentEventDO();
        event.setEventId("evt-" + UUID.randomUUID());
        event.setRegistrationId(registrationId);
        event.setTrialId(trialId);
        event.setCommandIdempotencyKey(commandIdempotencyKey);
        event.setCommandSha256(commandSha256);
        event.setEventSequence(sequence);
        event.setEventType(type);
        event.setFromStatus(from == null ? null : from.name());
        event.setToStatus(to.name());
        event.setReasonCode(reasonCode);
        event.setDetailMessage(detail);
        event.setObservedProvenanceSha256(provenanceSha256);
        if (result != null) {
            event.setResultArtifactUri(result.uri());
            event.setResultArtifactVersion(result.version());
            event.setResultArtifactSha256(result.sha256());
        }
        event.setRecordedBy(recordedBy);
        event.setOccurredAtMs(occurredAt);
        event.setPreviousEventSha256(previousHash);
        event.setEventSha256(ExperimentDigest.event(event));
        return event;
    }

    private ExperimentEventAudit toAudit(ExperimentEventDO event) {
        ExperimentResultArtifact result = event.getResultArtifactSha256() == null ? null
                : new ExperimentResultArtifact(event.getResultArtifactUri(),
                event.getResultArtifactVersion(), event.getResultArtifactSha256());
        return new ExperimentEventAudit(event.getEventId(), event.getTrialId(),
                event.getCommandIdempotencyKey(), event.getCommandSha256(),
                event.getEventSequence(), event.getEventType(),
                event.getFromStatus() == null ? null : ExperimentState.valueOf(event.getFromStatus()),
                ExperimentState.valueOf(event.getToStatus()), event.getReasonCode(),
                event.getDetailMessage(), event.getObservedProvenanceSha256(), result,
                event.getRecordedBy(), event.getOccurredAtMs(), event.getPreviousEventSha256(),
                event.getEventSha256());
    }

    private ExperimentArtifactDO toData(String registrationId,
                                        ExperimentArtifactReference artifact) {
        ExperimentArtifactDO data = new ExperimentArtifactDO();
        data.setRegistrationId(registrationId);
        data.setArtifactId(artifact.artifactId());
        data.setArtifactKind(artifact.kind().name());
        data.setArtifactVersion(artifact.version());
        data.setArtifactUri(artifact.uri());
        data.setArtifactSha256(artifact.sha256());
        data.setMediaType(artifact.mediaType());
        return data;
    }

    private ExperimentTrialDO toData(String registrationId, ExperimentTrialDefinition trial) {
        ExperimentTrialDO data = new ExperimentTrialDO();
        data.setRegistrationId(registrationId);
        data.setTrialId(trial.trialId());
        data.setTrialOrdinal(trial.ordinal());
        data.setHypothesis(trial.hypothesis());
        data.setRandomSeed(trial.randomSeed());
        data.setParameterSha256(trial.parameterSha256());
        return data;
    }

    private ExperimentRegistrationSnapshot snapshot(ExperimentRegistrationDO data) {
        return new ExperimentRegistrationSnapshot(data.getRegistrationId(), data.getIdempotencyKey(),
                data.getRegistrationSha256(), data.getExperimentName(), data.getHypothesis(),
                data.getProtocolVersion(), data.getModelVersion(), data.getPromptVersion(),
                data.getDeclaredTrialCount(), data.getRegisteredBy(), data.getRegisteredAtMs());
    }

    private ExperimentRegistrationReceipt receipt(ExperimentRegistrationDO data, boolean replay) {
        return new ExperimentRegistrationReceipt(data.getRegistrationId(),
                data.getRegistrationSha256(), data.getRegisteredAtMs(), replay);
    }

    private ExperimentPromotionEvidence ineligible(String registrationId, String sha256,
                                                   String reason) {
        String safeSha256 = null;
        try {
            safeSha256 = sha256 == null ? null
                    : ExperimentValidation.sha256(sha256, "registration sha256");
        } catch (IllegalArgumentException ignoredInvalidDigest) {
            // The refusal projection must itself remain available for malformed input.
        }
        ExperimentCertificationEvidence certification =
                ExperimentCertificationEvidence.notVerified(registrationId, safeSha256,
                        "CERTIFICATION_NOT_VERIFIED");
        return new ExperimentPromotionEvidence(registrationId, safeSha256, false, false,
                false, false, certification, false, List.of(reason));
    }

    private String operator(String value) {
        return ExperimentValidation.required(value, "operator", 100);
    }

    private void requireInserted(int count, String resource) {
        if (count != 1) throw new IllegalStateException("Failed to append immutable " + resource);
    }
}
