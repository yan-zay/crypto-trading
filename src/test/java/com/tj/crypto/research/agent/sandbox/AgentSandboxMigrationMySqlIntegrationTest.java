package com.tj.crypto.research.agent.sandbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MySQL 证明 V19 的审计、审批绑定和撤销证据不能原地改写。 */
@Testcontainers(disabledWithoutDocker = true)
class AgentSandboxMigrationMySqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
            .withDatabaseName("crypto_agent_sandbox")
            .withUsername("crypto_agent")
            .withPassword("crypto_agent");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @Test
    void auditProposalApprovalAndRevocationAreAppendOnly() {
        jdbc.update("""
                INSERT INTO agent_tool_audit
                    (event_id, call_id, session_id, agent_level, operation, outcome,
                     operator, policy_version, occurred_at_ms)
                VALUES ('audit-mysql', 'call-mysql', 'session-mysql', 'L2',
                        'SUBMIT_PAPER_PROPOSAL', 'STARTED', 'agent-operator',
                        'agent-sandbox-policy-v1', 1)
                """);
        assertImmutable("UPDATE agent_tool_audit SET outcome='SUCCEEDED' WHERE event_id='audit-mysql'");
        assertImmutable("DELETE FROM agent_tool_audit WHERE event_id='audit-mysql'");

        jdbc.update("""
                INSERT INTO agent_paper_shadow_proposal
                    (proposal_id, session_id, target_environment, approval_reference,
                     approval_artifact_sha256, approval_verification_receipt_sha256,
                     policy_version, created_at_ms, expires_at_ms,
                     approved_payload_sha256, status, proposal_json)
                VALUES ('proposal-mysql', 'session-mysql', 'PAPER', 'approval-mysql',
                        ?, ?, 'agent-sandbox-policy-v1', 1, 1000, ?, 'SUBMITTED', JSON_OBJECT())
                """, "a".repeat(64), "b".repeat(64), "c".repeat(64));
        assertImmutable("UPDATE agent_paper_shadow_proposal SET approval_reference='rewritten' "
                + "WHERE proposal_id='proposal-mysql'");
        assertImmutable("DELETE FROM agent_paper_shadow_proposal WHERE proposal_id='proposal-mysql'");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_paper_shadow_proposal
                    (proposal_id, session_id, target_environment, approval_reference,
                     approval_artifact_sha256, approval_verification_receipt_sha256,
                     policy_version, created_at_ms, expires_at_ms,
                     approved_payload_sha256, status, proposal_json)
                VALUES ('proposal-replay', 'session-other', 'PAPER', 'approval-mysql',
                        ?, ?, 'agent-sandbox-policy-v1', 2, 1001, ?, 'SUBMITTED', JSON_OBJECT())
                """, "d".repeat(64), "e".repeat(64), "f".repeat(64)))
                .hasMessageContaining("Duplicate entry");

        jdbc.update("""
                INSERT INTO agent_paper_shadow_proposal_revocation
                    (proposal_id, revoked_by, revoke_reason, revoked_at_ms)
                VALUES ('proposal-mysql', 'risk-controller', 'evidence expired', 2)
                """);
        assertImmutable("UPDATE agent_paper_shadow_proposal_revocation "
                + "SET revoke_reason='rewritten' WHERE proposal_id='proposal-mysql'");
        assertImmutable("DELETE FROM agent_paper_shadow_proposal_revocation "
                + "WHERE proposal_id='proposal-mysql'");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_paper_shadow_proposal_revocation "
                        + "WHERE proposal_id='proposal-mysql'",
                Integer.class)).isEqualTo(1);
    }

    private void assertImmutable(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).hasMessageContaining("immutable");
    }
}
