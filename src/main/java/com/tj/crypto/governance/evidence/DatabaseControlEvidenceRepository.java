package com.tj.crypto.governance.evidence;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/** MySQL append-only evidence index. Evidence artifacts remain outside the application database. */
@Repository
@RequiredArgsConstructor
public class DatabaseControlEvidenceRepository implements ControlEvidenceRepository {
    private final ControlEvidenceMapper mapper;

    @Override
    @Transactional
    public void append(ControlEvidence evidence) {
        try {
            mapper.insertHead(evidence.kind().name(), evidence.scope(), evidence.environment(),
                    evidence.subjectSha256(), evidence.policyVersion());
        } catch (DuplicateKeyException existingStream) {
            // Expected for retry/concurrent first append; the locked head below is authoritative.
        }
        ControlEvidenceHeadDO head = mapper.selectHeadForUpdate(
                evidence.kind().name(), evidence.scope(), evidence.environment(),
                evidence.subjectSha256(), evidence.policyVersion());
        if (head == null || head.getHeadVersion() == null || head.getHeadVersion() < 0) {
            throw new IllegalStateException("control evidence stream head is unavailable");
        }
        String currentEvidenceId = head.getLatestEvidenceId();
        if (evidence.evidenceId().equals(currentEvidenceId)) {
            ControlEvidenceDO stored = mapper.selectById(evidence.evidenceId());
            if (stored != null && evidence.equals(map(stored))) return;
            throw new IllegalStateException("evidence id was already used with different content");
        }
        String expectedPredecessor = currentEvidenceId == null ? "" : currentEvidenceId;
        if (!expectedPredecessor.equals(evidence.predecessorEvidenceId())) {
            throw new IllegalStateException("control evidence must extend the current append-only head");
        }
        int inserted = mapper.insert(evidence.evidenceId(), evidence.kind().name(), evidence.scope(),
                evidence.environment(), evidence.subjectSha256(), evidence.policyVersion(),
                evidence.status().name(), evidence.owner(), evidence.issuer(),
                evidence.startedAtMs(), evidence.completedAtMs(), evidence.expiresAtMs(),
                evidence.registeredAtMs(), evidence.artifactUri(), evidence.artifactSha256(),
                evidence.predecessorEvidenceId(), evidence.notes());
        if (inserted != 1) throw new IllegalStateException("control evidence was not appended");
        if (mapper.advanceHead(evidence.kind().name(), evidence.scope(), evidence.environment(),
                evidence.subjectSha256(), evidence.policyVersion(), head.getHeadVersion(),
                expectedPredecessor,
                evidence.evidenceId()) != 1) {
            throw new IllegalStateException("control evidence head changed concurrently");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ControlEvidence> findLatest(ControlKind kind, String scope, String environment,
                                                String subjectSha256, String policyVersion) {
        ControlEvidenceDO stored = mapper.selectLatest(kind.name(), scope,
                environment.toUpperCase(Locale.ROOT), subjectSha256, policyVersion);
        return Optional.ofNullable(stored).map(this::map);
    }

    private ControlEvidence map(ControlEvidenceDO source) {
        return new ControlEvidence(source.getEvidenceId(), ControlKind.valueOf(source.getControlKind()),
                source.getScope(), source.getEnvironment(), source.getSubjectSha256(),
                source.getPolicyVersion(), EvidenceStatus.valueOf(source.getEvidenceStatus()),
                source.getOwner(), source.getIssuer(), value(source.getStartedAtMs()),
                value(source.getCompletedAtMs()), value(source.getExpiresAtMs()),
                value(source.getRegisteredAtMs()), source.getArtifactUri(), source.getArtifactSha256(),
                source.getPredecessorEvidenceId(), source.getNotes());
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
