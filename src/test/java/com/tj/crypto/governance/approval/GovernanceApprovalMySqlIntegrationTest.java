package com.tj.crypto.governance.approval;

import com.tj.crypto.integration.mysql.MySqlTestImage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class GovernanceApprovalMySqlIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImage.pinned())
            .withDatabaseName("crypto_governance_approval")
            .withUsername("crypto_governance")
            .withPassword("crypto_governance");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void crossRequestReplayRollsBackParentAndReservationAtomically() {
        transactions.executeWithoutResult(status -> {
            insertParent("approval-a");
            insertReservation("shared-reservation", "approval-a", "risk-a", 0);
            insertReservation("reservation-a-2", "approval-a", "admin-a", 1);
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            insertParent("approval-b");
            insertReservation("shared-reservation", "approval-b", "risk-b", 0);
            insertReservation("reservation-b-2", "approval-b", "admin-b", 1);
        })).hasMessageContaining("Duplicate entry");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM governance_approval_consumption "
                + "WHERE request_id='approval-b'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM governance_approval_mfa_reservation "
                + "WHERE request_id='approval-b'", Integer.class)).isZero();
    }

    private static void insertParent(String requestId) {
        jdbc.update("""
                INSERT INTO governance_approval_consumption
                    (request_id, approval_action, resource_sha256,
                     governance_subject_sha256, requested_by, approved_by,
                     request_created_at_ms, request_expires_at_ms, consumed_at_ms)
                VALUES (?, 'STRATEGY_PROMOTE', ?, ?, 'requester', 'admin,risk', 1, 100, 10)
                """, requestId, "a".repeat(64), "b".repeat(64));
    }

    private static void insertReservation(String reservationId, String requestId,
                                          String approver, int ordinal) {
        jdbc.update("""
                INSERT INTO governance_approval_mfa_reservation
                    (replay_reservation_id, request_id, approver, reservation_ordinal)
                VALUES (?, ?, ?, ?)
                """, reservationId, requestId, approver, ordinal);
    }
}
