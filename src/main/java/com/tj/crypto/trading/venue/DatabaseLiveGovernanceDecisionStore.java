package com.tj.crypto.trading.venue;

import com.tj.crypto.governance.evidence.GovernanceSubject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
class DatabaseLiveGovernanceDecisionStore implements LiveGovernanceDecisionStore {
    private final LiveGovernanceDecisionMapper mapper;

    @Override
    public void append(LiveGovernanceDecision decision) {
        if (mapper.insert(toRow(decision)) != 1) {
            throw new IllegalStateException("live governance decision was not appended");
        }
        for (String reference : decision.evidenceReferences().split("\\n")) {
            int separator = reference.indexOf('=');
            if (separator <= 0 || separator == reference.length() - 1
                    || mapper.insertEvidence(decision.decisionId(),
                    reference.substring(0, separator), reference.substring(separator + 1)) != 1) {
                throw new IllegalStateException("live governance evidence link was not appended");
            }
        }
    }

    @Override
    public Optional<LiveGovernanceDecision> find(String decisionId) {
        return Optional.ofNullable(mapper.selectById(decisionId)).map(this::fromRow);
    }

    private LiveGovernanceDecisionDO toRow(LiveGovernanceDecision source) {
        GovernanceSubject subject = source.governanceSubject();
        LiveGovernanceDecisionDO row = new LiveGovernanceDecisionDO();
        row.setDecisionId(source.decisionId());
        row.setClientOrderId(source.clientOrderId());
        row.setCommandSha256(source.commandSha256());
        row.setExchange(source.exchange());
        row.setAccountId(source.accountId());
        row.setStrategyId(source.strategyId());
        row.setReleaseAssignmentId(source.releaseAssignmentId());
        row.setReleaseAssignmentVersion(source.releaseAssignmentVersion());
        row.setStrategyArtifactSha256(source.strategyArtifactSha256());
        row.setGovernanceSubjectSha256(source.governanceSubjectSha256());
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
        row.setEvidenceSetSha256(source.evidenceSetSha256());
        row.setEvidenceReferences(source.evidenceReferences());
        row.setApprovalRequestId(source.approvalRequestId());
        row.setRequestedBy(source.requestedBy());
        row.setAuthorizedAtMs(source.authorizedAtMs());
        row.setExpiresAtMs(source.expiresAtMs());
        return row;
    }

    private LiveGovernanceDecision fromRow(LiveGovernanceDecisionDO row) {
        GovernanceSubject subject = new GovernanceSubject(row.getGovernanceScope(),
                row.getGovernanceEnvironment(), row.getAccountScope(), row.getGitCommit(),
                row.getBuildSha256(), row.getContainerImageDigest(), row.getConfigurationSha256(),
                row.getDatasetManifestSha256(), row.getStrategyArtifactSha256(),
                row.getKeyReference(), row.getRunId(), row.getPolicyVersion());
        if (!subject.sha256().equals(row.getGovernanceSubjectSha256())) {
            throw new IllegalStateException("persisted governance subject digest is inconsistent");
        }
        return new LiveGovernanceDecision(row.getDecisionId(), row.getClientOrderId(),
                row.getCommandSha256(), row.getExchange(), row.getAccountId(), row.getStrategyId(),
                row.getReleaseAssignmentId(), row.getReleaseAssignmentVersion(),
                row.getStrategyArtifactSha256(), subject, row.getEvidenceSetSha256(),
                row.getEvidenceReferences(), row.getApprovalRequestId(), row.getRequestedBy(),
                row.getAuthorizedAtMs(), row.getExpiresAtMs());
    }
}
