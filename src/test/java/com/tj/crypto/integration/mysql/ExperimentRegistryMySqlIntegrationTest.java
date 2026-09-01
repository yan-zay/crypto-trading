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

/** MySQL proof that V16 installs and the append-only evidence cannot be rewritten. */
@Testcontainers(disabledWithoutDocker = true)
class ExperimentRegistryMySqlIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = MySqlTestImage.container()
            .withDatabaseName("crypto_experiment_registry")
            .withUsername("crypto_experiment")
            .withPassword("crypto_experiment");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void registrationArtifactTrialAndEventTablesRejectUpdateAndDelete() {
        jdbc.update("""
                INSERT INTO research_experiment_registration
                    (registration_id, idempotency_key, registration_sha256, experiment_name,
                     hypothesis, protocol_version, model_version, prompt_version,
                     declared_trial_count, registered_by, registered_at_ms)
                VALUES ('exp-mysql', 'idem-mysql', ?, 'mysql family', 'hypothesis',
                        'protocol-v1', 'NONE', 'NONE', 1, 'integration-test', 1)
                """, "a".repeat(64));
        jdbc.update("""
                INSERT INTO research_experiment_chain_head
                    (registration_id, last_event_sequence, last_event_sha256, version)
                VALUES ('exp-mysql', -1, ?, 0)
                """, "0".repeat(64));
        jdbc.update("""
                INSERT INTO research_experiment_artifact
                    (registration_id, artifact_id, artifact_kind, artifact_version,
                     artifact_uri, artifact_sha256, media_type)
                VALUES ('exp-mysql', 'dataset', 'DATASET', 'v1', 'immutable://dataset', ?,
                        'application/x-parquet')
                """, "b".repeat(64));
        jdbc.update("""
                INSERT INTO research_experiment_trial
                    (registration_id, trial_id, trial_ordinal, hypothesis, random_seed,
                     parameter_sha256)
                VALUES ('exp-mysql', 'trial-0', 0, 'trial hypothesis', 7, ?)
                """, "c".repeat(64));
        jdbc.update("""
                INSERT INTO research_experiment_event
                    (event_id, registration_id, trial_id, command_idempotency_key,
                     command_sha256, event_sequence, event_type,
                     from_status, to_status, recorded_by, occurred_at_ms,
                     previous_event_sha256, event_sha256)
                VALUES ('evt-mysql-family', 'exp-mysql', NULL, 'family:registered',
                        ?, 0, 'FAMILY_REGISTERED', NULL, 'REGISTERED',
                        'integration-test', 1, ?, ?)
                """, "d".repeat(64), "0".repeat(64), "e".repeat(64));

        assertDefinitionSealed(() -> jdbc.update("""
                INSERT INTO research_experiment_artifact
                    (registration_id, artifact_id, artifact_kind, artifact_version,
                     artifact_uri, artifact_sha256, media_type)
                VALUES ('exp-mysql', 'late-code', 'CODE', 'v1',
                        'immutable://late-code', ?, 'application/java-archive')
                """, "7".repeat(64)), "artifact definitions are sealed");
        assertDefinitionSealed(() -> jdbc.update("""
                INSERT INTO research_experiment_trial
                    (registration_id, trial_id, trial_ordinal, hypothesis, random_seed,
                     parameter_sha256)
                VALUES ('exp-mysql', 'late-trial', 1, 'post-selection trial', 8, ?)
                """, "8".repeat(64)), "trial definitions are sealed");

        assertThat(jdbc.update("""
                UPDATE research_experiment_chain_head
                SET last_event_sequence=0, last_event_sha256=?, version=1
                WHERE registration_id='exp-mysql' AND last_event_sequence=-1
                  AND last_event_sha256=? AND version=0
                """, "e".repeat(64), "0".repeat(64))).isEqualTo(1);
        jdbc.update("""
                INSERT INTO research_experiment_event
                    (event_id, registration_id, trial_id, command_idempotency_key,
                     command_sha256, event_sequence, event_type,
                     from_status, to_status, recorded_by, occurred_at_ms,
                     previous_event_sha256, event_sha256)
                VALUES ('evt-mysql-trial', 'exp-mysql', 'trial-0',
                        'trial:trial-0:registered', ?, 1, 'TRIAL_REGISTERED',
                        NULL, 'REGISTERED', 'integration-test', 2, ?, ?)
                """, "f".repeat(64), "e".repeat(64), "1".repeat(64));
        assertThat(jdbc.update("""
                UPDATE research_experiment_chain_head
                SET last_event_sequence=1, last_event_sha256=?, version=2
                WHERE registration_id='exp-mysql' AND last_event_sequence=0
                  AND last_event_sha256=? AND version=1
                """, "1".repeat(64), "e".repeat(64))).isEqualTo(1);

        assertImmutable("UPDATE research_experiment_registration SET hypothesis='rewritten' "
                + "WHERE registration_id='exp-mysql'");
        assertImmutable("DELETE FROM research_experiment_artifact "
                + "WHERE registration_id='exp-mysql' AND artifact_id='dataset'");
        assertImmutable("UPDATE research_experiment_trial SET random_seed=8 "
                + "WHERE registration_id='exp-mysql' AND trial_id='trial-0'");
        assertImmutable("DELETE FROM research_experiment_event WHERE event_id='evt-mysql-trial'");

        assertThat(jdbc.update("""
                UPDATE research_experiment_chain_head SET version=version+1
                WHERE registration_id='exp-mysql' AND version=999
                """)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO research_experiment_event
                    (event_id, registration_id, trial_id, command_idempotency_key,
                     command_sha256, event_sequence, event_type, from_status, to_status,
                     recorded_by, occurred_at_ms, previous_event_sha256, event_sha256)
                VALUES ('evt-duplicate-command', 'exp-mysql', 'trial-0',
                        'trial:trial-0:registered', ?, 2, 'TRIAL_REGISTERED', NULL,
                        'REGISTERED', 'integration-test', 3, ?, ?)
                """, "2".repeat(64), "1".repeat(64), "3".repeat(64)))
                .hasMessageContaining("uk_research_experiment_event_command");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM research_experiment_registration
                WHERE registration_id='exp-mysql' AND hypothesis='hypothesis'
                """, Integer.class)).isEqualTo(1);
    }

    private void assertImmutable(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .hasMessageContaining("immutable");
    }

    private void assertDefinitionSealed(Runnable append, String message) {
        assertThatThrownBy(append::run).hasMessageContaining(message);
    }
}
