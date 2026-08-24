package com.tj.crypto.strategy.lifecycle;

import com.tj.crypto.governance.evidence.GovernanceSubject;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
class MyBatisStrategyLifecycleStore implements StrategyLifecycleStore {
    private final StrategyLifecycleMapper mapper;

    MyBatisStrategyLifecycleStore(StrategyLifecycleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertArtifact(SignedStrategyArtifact artifact) {
        if (mapper.insertArtifact(artifactRow(artifact)) != 1) throw new IllegalStateException("artifact insert failed");
    }

    @Override
    public Optional<SignedStrategyArtifact> findArtifact(String digest) {
        StrategyArtifactRow row = mapper.selectArtifact(digest);
        return Optional.ofNullable(row == null ? null : artifact(row));
    }

    @Override
    public Optional<StrategyLifecycleState> lockState(String strategyName) {
        StrategyLifecycleStateRow row = mapper.selectStateForUpdate(strategyName);
        return Optional.ofNullable(row == null ? null : state(row));
    }

    @Override
    public void insertState(StrategyLifecycleState state) {
        if (mapper.insertState(stateRow(state)) != 1) throw new IllegalStateException("lifecycle state insert failed");
    }

    @Override
    public boolean updateState(StrategyLifecycleState state, long expectedVersion) {
        return mapper.updateState(stateRow(state), expectedVersion) == 1;
    }

    @Override
    public void insertApproval(StrategyApproval approval) {
        StrategyApprovalRow row = new StrategyApprovalRow();
        row.setApprovalId(approval.approvalId());
        row.setStrategyName(approval.strategyName());
        row.setArtifactSha256(approval.artifactSha256());
        row.setReviewCycle(approval.reviewCycle());
        row.setDecision(approval.decision().name());
        row.setApprovalScopeSha256(approval.approvalScopeSha256());
        row.setEvidenceSetSha256(approval.evidenceSetSha256());
        row.setExpiresAtMs(approval.expiresAt().toEpochMilli());
        row.setApprovedBy(approval.approvedBy());
        row.setApprovedAtMs(approval.approvedAt().toEpochMilli());
        if (mapper.insertApproval(row) != 1) throw new IllegalStateException("approval insert failed");
    }

    @Override
    public List<StrategyApproval> approvals(String strategyName, String digest, long reviewCycle) {
        return mapper.selectApprovals(strategyName, digest, reviewCycle).stream().map(row ->
                new StrategyApproval(row.getApprovalId(), row.getStrategyName(), row.getArtifactSha256(),
                        row.getReviewCycle(), ApprovalDecision.valueOf(row.getDecision()),
                        row.getApprovalScopeSha256(), row.getEvidenceSetSha256(),
                        Instant.ofEpochMilli(row.getExpiresAtMs()), row.getApprovedBy(),
                        Instant.ofEpochMilli(row.getApprovedAtMs()))).toList();
    }

    @Override
    public void insertEvidence(StrategyEvidenceSnapshot evidence) {
        StrategyEvidenceRow row = new StrategyEvidenceRow();
        row.setSnapshotId(evidence.snapshotId());
        row.setStrategyName(evidence.strategyName());
        row.setArtifactSha256(evidence.artifactSha256());
        row.setEvidenceType(evidence.type().name());
        row.setOutcome(evidence.outcome().name());
        row.setEvidenceSha256(evidence.evidenceSha256());
        row.setEvidenceUri(evidence.evidenceUri());
        row.setCapturedAtMs(evidence.capturedAt().toEpochMilli());
        row.setExpiresAtMs(evidence.expiresAt().toEpochMilli());
        row.setRecordedBy(evidence.recordedBy());
        row.setRecordedAtMs(evidence.recordedAt().toEpochMilli());
        if (mapper.insertEvidence(row) != 1) throw new IllegalStateException("evidence insert failed");
    }

    @Override
    public List<StrategyEvidenceSnapshot> evidence(String strategyName, String digest) {
        return mapper.selectEvidence(strategyName, digest).stream().map(row ->
                new StrategyEvidenceSnapshot(row.getSnapshotId(), row.getStrategyName(),
                        row.getArtifactSha256(), StrategyEvidenceType.valueOf(row.getEvidenceType()),
                        EvidenceOutcome.valueOf(row.getOutcome()), row.getEvidenceSha256(),
                        row.getEvidenceUri(), Instant.ofEpochMilli(row.getCapturedAtMs()),
                        Instant.ofEpochMilli(row.getExpiresAtMs()), row.getRecordedBy(),
                        Instant.ofEpochMilli(row.getRecordedAtMs()))).toList();
    }

    @Override
    public Optional<StrategyLifecycleEvent> latestEvent(String strategyName) {
        StrategyLifecycleEventRow row = mapper.selectLatestEvent(strategyName);
        return Optional.ofNullable(row == null ? null : event(row));
    }

    @Override
    public void appendEvent(StrategyLifecycleEvent event) {
        StrategyLifecycleEventRow row = new StrategyLifecycleEventRow();
        row.setEventId(event.eventId());
        row.setStrategyName(event.strategyName());
        row.setArtifactSha256(event.artifactSha256());
        row.setSequenceNo(event.sequenceNo());
        row.setReviewCycle(event.reviewCycle());
        row.setFromStage(event.fromStage() == null ? null : event.fromStage().name());
        row.setToStage(event.toStage() == null ? null : event.toStage().name());
        row.setAction(event.action().name());
        row.setAccepted(event.accepted());
        row.setReasonCode(event.reason().name());
        row.setDetailMessage(event.detail());
        row.setOperator(event.operator());
        row.setEvidenceSetSha256(event.evidenceSetSha256());
        row.setOccurredAtMs(event.occurredAt().toEpochMilli());
        row.setPreviousEventSha256(event.previousEventSha256());
        row.setEventSha256(event.eventSha256());
        if (mapper.insertEvent(row) != 1) throw new IllegalStateException("lifecycle audit insert failed");
    }

    @Override
    public Optional<StrategyReleaseAssignment> lockReleaseAssignment(String strategyName) {
        mapper.insertReleaseAssignmentHeadIfMissing(strategyName);
        StrategyReleaseAssignmentHeadRow head = mapper.selectReleaseAssignmentHeadForUpdate(strategyName);
        if (head == null) throw new IllegalStateException("release assignment head is missing");
        if (head.getCurrentAssignmentId() == null) return Optional.empty();
        return Optional.ofNullable(mapper.selectReleaseAssignment(strategyName)).map(this::releaseAssignment);
    }

    @Override
    public Optional<StrategyReleaseAssignment> findReleaseAssignment(String strategyName) {
        return Optional.ofNullable(mapper.selectReleaseAssignment(strategyName)).map(this::releaseAssignment);
    }

    @Override
    public void replaceReleaseAssignment(StrategyReleaseAssignment assignment, long expectedVersion) {
        StrategyReleaseAssignmentRow row = releaseAssignmentRow(assignment);
        if (mapper.insertReleaseAssignment(row) != 1) {
            throw new IllegalStateException("release assignment insert failed");
        }
        if (mapper.advanceReleaseAssignmentHead(assignment.strategyName(), assignment.assignmentId(),
                assignment.assignmentVersion(), expectedVersion) != 1) {
            throw new IllegalStateException(LifecycleReason.CONCURRENT_UPDATE.name());
        }
    }

    private StrategyArtifactRow artifactRow(SignedStrategyArtifact artifact) {
        StrategyArtifactRow row = new StrategyArtifactRow();
        row.setArtifactSha256(artifact.artifactSha256());
        row.setStrategyName(artifact.strategyName());
        row.setArtifactVersion(artifact.artifactVersion());
        row.setArtifactContent(artifact.content());
        row.setMediaType(artifact.mediaType());
        row.setArtifactUri(artifact.artifactUri());
        row.setSignatureAlgorithm(artifact.signatureAlgorithm());
        row.setSigningKeyId(artifact.signingKeyId());
        row.setSignatureBase64(artifact.signatureBase64());
        row.setSignedAtMs(artifact.signedAt().toEpochMilli());
        row.setExpiresAtMs(artifact.expiresAt().toEpochMilli());
        row.setSubmittedBy(artifact.submittedBy());
        row.setSubmittedAtMs(artifact.submittedAt().toEpochMilli());
        row.setResearchRegistrationId(artifact.researchRegistrationId());
        row.setResearchRegistrationSha256(artifact.researchRegistrationSha256());
        return row;
    }

    private SignedStrategyArtifact artifact(StrategyArtifactRow row) {
        return new SignedStrategyArtifact(row.getStrategyName(), row.getArtifactVersion(),
                row.getArtifactContent(), row.getArtifactSha256(), row.getMediaType(), row.getArtifactUri(),
                row.getSignatureAlgorithm(), row.getSigningKeyId(), row.getSignatureBase64(),
                Instant.ofEpochMilli(row.getSignedAtMs()), Instant.ofEpochMilli(row.getExpiresAtMs()),
                row.getSubmittedBy(), Instant.ofEpochMilli(row.getSubmittedAtMs()),
                row.getResearchRegistrationId(), row.getResearchRegistrationSha256());
    }

    private StrategyLifecycleStateRow stateRow(StrategyLifecycleState state) {
        StrategyLifecycleStateRow row = new StrategyLifecycleStateRow();
        row.setStrategyName(state.strategyName());
        row.setArtifactSha256(state.artifactSha256());
        row.setPreviousArtifactSha256(state.previousArtifactSha256());
        row.setStage(state.stage().name());
        row.setReviewCycle(state.reviewCycle());
        row.setStateVersion(state.stateVersion());
        row.setUpdatedBy(state.updatedBy());
        row.setUpdatedAtMs(state.updatedAt().toEpochMilli());
        row.setPolicyReason(state.policyReason().name());
        return row;
    }

    private StrategyLifecycleState state(StrategyLifecycleStateRow row) {
        return new StrategyLifecycleState(row.getStrategyName(), row.getArtifactSha256(),
                row.getPreviousArtifactSha256(), StrategyLifecycleStage.valueOf(row.getStage()),
                row.getReviewCycle(), row.getStateVersion(), row.getUpdatedBy(),
                Instant.ofEpochMilli(row.getUpdatedAtMs()), LifecycleReason.valueOf(row.getPolicyReason()));
    }

    private StrategyLifecycleEvent event(StrategyLifecycleEventRow row) {
        return new StrategyLifecycleEvent(row.getEventId(), row.getStrategyName(), row.getArtifactSha256(),
                row.getSequenceNo(), row.getReviewCycle(),
                row.getFromStage() == null ? null : StrategyLifecycleStage.valueOf(row.getFromStage()),
                row.getToStage() == null ? null : StrategyLifecycleStage.valueOf(row.getToStage()),
                StrategyLifecycleAction.valueOf(row.getAction()), Boolean.TRUE.equals(row.getAccepted()),
                LifecycleReason.valueOf(row.getReasonCode()), row.getDetailMessage(), row.getOperator(),
                row.getEvidenceSetSha256(), Instant.ofEpochMilli(row.getOccurredAtMs()),
                row.getPreviousEventSha256(), row.getEventSha256());
    }

    private StrategyReleaseAssignmentRow releaseAssignmentRow(StrategyReleaseAssignment assignment) {
        GovernanceSubject subject = assignment.governanceSubject();
        StrategyReleaseAssignmentRow row = new StrategyReleaseAssignmentRow();
        row.setAssignmentId(assignment.assignmentId());
        row.setStrategyName(assignment.strategyName());
        row.setArtifactSha256(assignment.artifactSha256());
        row.setMinimumStage(assignment.minimumStage().name());
        row.setGovernanceScope(subject.scope());
        row.setGovernanceEnvironment(subject.environment());
        row.setAccountScope(subject.accountScope());
        row.setGitCommit(subject.gitCommit());
        row.setBuildSha256(subject.buildSha256());
        row.setContainerImageDigest(subject.containerImageDigest());
        row.setConfigurationSha256(subject.configurationSha256());
        row.setDatasetManifestSha256(subject.datasetManifestSha256());
        row.setKeyReference(subject.keyReference());
        row.setRunId(subject.runId());
        row.setPolicyVersion(subject.policyVersion());
        row.setGovernanceSubjectSha256(subject.sha256());
        row.setExpiresAtMs(assignment.expiresAt().toEpochMilli());
        row.setAssignmentVersion(assignment.assignmentVersion());
        row.setAssignedBy(assignment.assignedBy());
        row.setAssignedAtMs(assignment.assignedAt().toEpochMilli());
        return row;
    }

    private StrategyReleaseAssignment releaseAssignment(StrategyReleaseAssignmentRow row) {
        GovernanceSubject subject = new GovernanceSubject(row.getGovernanceScope(),
                row.getGovernanceEnvironment(), row.getAccountScope(), row.getGitCommit(),
                row.getBuildSha256(), row.getContainerImageDigest(), row.getConfigurationSha256(),
                row.getDatasetManifestSha256(), row.getArtifactSha256(), row.getKeyReference(),
                row.getRunId(), row.getPolicyVersion());
        if (!subject.sha256().equals(row.getGovernanceSubjectSha256())) {
            throw new IllegalStateException("persisted governance subject digest mismatch");
        }
        return new StrategyReleaseAssignment(row.getAssignmentId(), row.getStrategyName(),
                row.getArtifactSha256(), StrategyLifecycleStage.valueOf(row.getMinimumStage()),
                subject, Instant.ofEpochMilli(row.getExpiresAtMs()), row.getAssignmentVersion(),
                row.getAssignedBy(), Instant.ofEpochMilli(row.getAssignedAtMs()));
    }
}
