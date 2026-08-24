package com.tj.crypto.research.agent.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSandboxMigrationContractTest {

    @Test
    void v19DeclaresImmutableAuditProposalAndRevocationEvidence() throws IOException {
        String migration = new ClassPathResource(
                "db/migration/V19__create_agent_sandbox_audit.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("trg_agent_tool_audit_no_update")
                .contains("trg_agent_tool_audit_no_delete")
                .contains("trg_agent_proposal_no_update")
                .contains("trg_agent_proposal_no_delete")
                .contains("CREATE TABLE agent_paper_shadow_proposal_revocation")
                .contains("trg_agent_proposal_revocation_no_update")
                .contains("trg_agent_proposal_revocation_no_delete")
                .contains("UNIQUE KEY uk_agent_proposal_approval (approval_reference)")
                .contains("approval_verification_receipt_sha256 CHAR(64) NOT NULL");
    }
}
