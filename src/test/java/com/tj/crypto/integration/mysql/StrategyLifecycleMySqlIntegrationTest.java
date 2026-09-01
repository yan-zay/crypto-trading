package com.tj.crypto.integration.mysql;

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

/** MySQL proof for V17/V21 lifecycle immutability, constraints, and release-head CAS. */
@Testcontainers(disabledWithoutDocker = true)
class StrategyLifecycleMySqlIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = MySqlTestImage.container()
            .withDatabaseName("crypto_strategy_lifecycle")
            .withUsername("crypto_lifecycle")
            .withPassword("crypto_lifecycle");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndSeed() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("""
                INSERT INTO strategy_signing_key
                    (key_id, algorithm, public_key_x509_base64, status, valid_from_ms,
                     expires_at_ms, provisioned_by, provisioned_at_ms)
                VALUES ('mysql-key', 'Ed25519', 'public-key', 'ACTIVE', 1, 9999999999999,
                        'integration-test', 1)
                """);
        jdbc.update("""
                INSERT INTO strategy_artifact
                    (artifact_sha256, strategy_name, artifact_version, artifact_content,
                     media_type, artifact_uri, signature_algorithm, signing_key_id,
                     signature_base64, signed_at_ms, expires_at_ms, submitted_by,
                     submitted_at_ms, research_registration_id, research_registration_sha256)
                VALUES (?, 'mysql-strategy', 'v1', X'01', 'application/json',
                        'artifact://mysql/v1', 'Ed25519', 'mysql-key', 'signature', 1,
                        9999999999999, 'author', 2, 'exp-mysql', ?)
                """, "a".repeat(64), "b".repeat(64));
        jdbc.update("""
                INSERT INTO strategy_lifecycle_state
                    (strategy_name, artifact_sha256, stage, review_cycle, state_version,
                     updated_by, updated_at_ms, policy_reason)
                VALUES ('mysql-strategy', ?, 'REVIEW', 1, 0, 'author', 2, 'ACCEPTED')
                """, "a".repeat(64));
        jdbc.update("""
                INSERT INTO strategy_lifecycle_approval
                    (approval_id, strategy_name, artifact_sha256, review_cycle, decision,
                     approval_scope_sha256, evidence_set_sha256, expires_at_ms,
                     approved_by, approved_at_ms)
                VALUES ('approval-mysql', 'mysql-strategy', ?, 1, 'APPROVE', ?, ?,
                        9999999999999, 'reviewer-1', 3)
                """, "a".repeat(64), "c".repeat(64), "d".repeat(64));
        jdbc.update("""
                INSERT INTO strategy_evidence_snapshot
                    (snapshot_id, strategy_name, artifact_sha256, evidence_type, outcome,
                     evidence_sha256, evidence_uri, captured_at_ms, expires_at_ms,
                     recorded_by, recorded_at_ms)
                VALUES ('evidence-mysql', 'mysql-strategy', ?, 'RESEARCH', 'PASS', ?,
                        'registry://experiments/exp-mysql', 2, 9999999999999,
                        'evidence-officer', 3)
                """, "a".repeat(64), "e".repeat(64));
        jdbc.update("""
                INSERT INTO strategy_lifecycle_transition
                    (event_id, strategy_name, artifact_sha256, sequence_no, review_cycle,
                     from_stage, to_stage, action, accepted, reason_code, detail_message,
                     operator, evidence_set_sha256, occurred_at_ms,
                     previous_event_sha256, event_sha256)
                VALUES ('event-mysql', 'mysql-strategy', ?, 1, 1, 'DRAFT', 'REVIEW',
                        'SUBMIT_REVIEW', 1, 'ACCEPTED', 'review requested', 'author', ?, 3, ?, ?)
                """, "a".repeat(64), "d".repeat(64), "0".repeat(64), "f".repeat(64));
    }

    @Test
    void freshMigrationInstallsV17AndV21AndAppendOnlyRowsCannotBeRewritten() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('17','21')",
                Integer.class)).isEqualTo(2);
        assertImmutable("UPDATE strategy_artifact SET artifact_version='rewritten' "
                + "WHERE artifact_sha256='" + "a".repeat(64) + "'");
        assertImmutable("DELETE FROM strategy_lifecycle_approval WHERE approval_id='approval-mysql'");
        assertImmutable("UPDATE strategy_evidence_snapshot SET outcome='FAIL' "
                + "WHERE snapshot_id='evidence-mysql'");
        assertImmutable("DELETE FROM strategy_lifecycle_transition WHERE event_id='event-mysql'");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO strategy_lifecycle_state
                    (strategy_name, artifact_sha256, stage, review_cycle, state_version,
                     updated_by, updated_at_ms, policy_reason)
                VALUES ('invalid-stage', ?, 'LIVE', 0, 0, 'attacker', 1, 'ACCEPTED')
                """, "a".repeat(64))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void immutableReleaseVersionsAdvanceThroughSingleWinnerCas() {
        jdbc.update("""
                INSERT INTO strategy_release_assignment_head
                    (strategy_name, current_assignment_id, current_assignment_version)
                VALUES ('mysql-strategy', NULL, -1)
                """);
        insertAssignment("assignment-a", 0, "1".repeat(64));
        insertAssignment("assignment-b", 1, "2".repeat(64));

        int winner = jdbc.update("""
                UPDATE strategy_release_assignment_head
                SET current_assignment_id='assignment-a', current_assignment_version=0
                WHERE strategy_name='mysql-strategy' AND current_assignment_version=-1
                """);
        int stale = jdbc.update("""
                UPDATE strategy_release_assignment_head
                SET current_assignment_id='assignment-b', current_assignment_version=1
                WHERE strategy_name='mysql-strategy' AND current_assignment_version=-1
                """);
        assertThat(winner).isEqualTo(1);
        assertThat(stale).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT current_assignment_id FROM strategy_release_assignment_head
                WHERE strategy_name='mysql-strategy'
                """, String.class)).isEqualTo("assignment-a");
        assertImmutable("UPDATE strategy_release_assignment SET run_id='rewritten' "
                + "WHERE assignment_id='assignment-a'");
    }

    private void insertAssignment(String id, long version, String subjectSha) {
        jdbc.update("""
                INSERT INTO strategy_release_assignment
                    (assignment_id, strategy_name, artifact_sha256, minimum_stage,
                     governance_scope, governance_environment, account_scope, git_commit,
                     build_sha256, container_image_digest, configuration_sha256,
                     dataset_manifest_sha256, key_reference, run_id, policy_version,
                     governance_subject_sha256, expires_at_ms, assignment_version,
                     assigned_by, assigned_at_ms)
                VALUES (?, 'mysql-strategy', ?, 'SHADOW', 'strategy:mysql-strategy', 'TEST',
                        'paper-account', 'abcdef123456', ?, ?, ?, ?, 'kms://test/key',
                        'run-1', 'policy-v1', ?, 9999999999999, ?, 'release-manager', 3)
                """, id, "a".repeat(64), "3".repeat(64), "sha256:" + "4".repeat(64),
                "5".repeat(64), "6".repeat(64), subjectSha, version);
    }

    private void assertImmutable(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql)).hasMessageContaining("immutable");
    }
}
