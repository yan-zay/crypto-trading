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

/** MySQL 8 evidence for V15 foreign keys, lowercase SHA checks and append-only triggers. */
@Testcontainers(disabledWithoutDocker = true)
class MarketDataCatalogMySqlIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
            .withDatabaseName("crypto_market_catalog")
            .withUsername("crypto_market")
            .withPassword("crypto_market");

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
    void manifestRequiresCatalogedDatasetAndObjectAndAllFactsAreImmutable() {
        String schemaSha = "a".repeat(64);
        String objectSha = "b".repeat(64);
        String manifestSha = "c".repeat(64);
        String requestSha = "d".repeat(64);
        jdbc.update("""
                INSERT INTO market_data_dataset
                    (dataset_version_id, dataset_id, dataset_version, schema_name, schema_version,
                     schema_fingerprint_sha256, catalog_state, created_by, created_at_ms)
                VALUES ('dataset-v1', 'btc-benchmark', 'v1', 'canonical-market-event-parquet', '1',
                        ?, 'VERIFIED', 'integration-test', 1)
                """, schemaSha);
        jdbc.update("""
                INSERT INTO market_data_object_catalog
                    (object_sha256, storage_uri, content_type, byte_size,
                     verification_state, verified_at_ms, created_at_ms)
                VALUES (?, 's3://immutable-test/btc/events.parquet', 'application/vnd.apache.parquet',
                        128, 'VERIFIED', 2, 1)
                """, objectSha);
        jdbc.update("""
                INSERT INTO market_data_manifest
                    (manifest_sha256, dataset_version_id, idempotency_key, manifest_version,
                     event_type, source, exchange, market_type, symbol, event_date_utc,
                     schema_fingerprint_sha256, request_sha256, data_object_sha256, row_count,
                     start_inclusive_ms, end_exclusive_ms, source_uri,
                     immutable_source_snapshot_id, writer_version, created_at_ms)
                VALUES (?, 'dataset-v1', 'batch-1', '1', 'TRADE', 'BINANCE', 'BINANCE',
                        'PERPETUAL', 'BTCUSDT', '2026-08-20', ?, ?, ?, 1, 1000, 1001,
                        'urn:crypto-trading:market-journal:test:snapshot', 'snapshot-1',
                        'parquet-avro-1.17.1/archive-v1', 3)
                """, manifestSha, schemaSha, requestSha, objectSha);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_data_manifest", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE market_data_manifest SET row_count=2 WHERE manifest_sha256=?", manifestSha))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE market_data_dataset SET description='rewritten' WHERE dataset_version_id='dataset-v1'"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM market_data_object_catalog WHERE object_sha256=?", objectSha))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO market_data_manifest
                    (manifest_sha256, dataset_version_id, idempotency_key, manifest_version,
                     event_type, source, exchange, market_type, symbol, event_date_utc,
                     schema_fingerprint_sha256, request_sha256, data_object_sha256, row_count,
                     start_inclusive_ms, end_exclusive_ms, source_uri,
                     immutable_source_snapshot_id, writer_version, created_at_ms)
                VALUES (?, 'missing-dataset', 'batch-2', '1', 'TRADE', 'BINANCE', 'BINANCE',
                        'PERPETUAL', 'BTCUSDT', '2026-08-20', ?, ?, ?, 1, 1000, 1001,
                        'https://data.example.test/batch-2', 'snapshot-2', 'writer-v1', 3)
                """, "e".repeat(64), schemaSha, requestSha, objectSha))
                .hasMessageContaining("foreign key");
    }

    @Test
    void rejectsUppercaseOrMalformedContentHashes() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO market_data_object_catalog
                    (object_sha256, storage_uri, content_type, byte_size,
                     verification_state, created_at_ms)
                VALUES (?, 's3://immutable-test/invalid.parquet', 'application/vnd.apache.parquet',
                        1, 'REGISTERED', 1)
                """, "A".repeat(64)))
                .hasMessageContaining("Check constraint");
    }
}
