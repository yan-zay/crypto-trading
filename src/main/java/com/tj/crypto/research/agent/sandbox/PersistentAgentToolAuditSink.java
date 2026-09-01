package com.tj.crypto.research.agent.sandbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PersistentAgentToolAuditSink implements AgentToolAuditSink {
    private final AgentToolAuditMapper mapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(AgentToolAuditEvent event) {
        AgentToolAuditDO target = new AgentToolAuditDO();
        target.setEventId(event.eventId());
        target.setCallId(event.callId());
        target.setSessionId(event.sessionId());
        target.setAgentLevel(event.agentLevel().name());
        target.setOperation(event.operation());
        target.setOutcome(event.outcome().name());
        target.setOperator(event.operator());
        target.setPolicyVersion(event.policyVersion());
        target.setInputSha256(event.inputSha256());
        target.setApprovalReference(event.approvalReference());
        target.setModelVersion(event.modelVersion());
        target.setPromptVersion(event.promptVersion());
        target.setModelOutputSha256(event.modelOutputSha256());
        target.setEvalReportSha256(event.evalReportSha256());
        target.setOccurredAtMs(event.occurredAt().toEpochMilli());
        target.setElapsedMs(event.elapsedMs());
        target.setReasonCode(event.reasonCode());
        target.setResultReference(event.resultReference());
        target.setDetail(event.detail());
        if (mapper.insert(target) != 1) {
            throw new IllegalStateException("Agent audit append failed");
        }
    }
}
