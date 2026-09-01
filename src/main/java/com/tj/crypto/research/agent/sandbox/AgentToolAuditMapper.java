package com.tj.crypto.research.agent.sandbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentToolAuditMapper {
    @Insert("""
            INSERT INTO agent_tool_audit
                (event_id, call_id, session_id, agent_level, operation, outcome,
                 operator, policy_version, input_sha256, approval_reference, model_version,
                 prompt_version, model_output_sha256, eval_report_sha256,
                 occurred_at_ms, elapsed_ms, reason_code,
                 result_reference, detail)
            VALUES
                (#{eventId}, #{callId}, #{sessionId}, #{agentLevel}, #{operation}, #{outcome},
                 #{operator}, #{policyVersion}, #{inputSha256}, #{approvalReference}, #{modelVersion},
                 #{promptVersion}, #{modelOutputSha256}, #{evalReportSha256},
                 #{occurredAtMs}, #{elapsedMs}, #{reasonCode},
                 #{resultReference}, #{detail})
            """)
    int insert(AgentToolAuditDO event);
}
