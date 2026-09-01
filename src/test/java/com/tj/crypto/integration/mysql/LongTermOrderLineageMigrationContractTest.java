package com.tj.crypto.integration.mysql;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.internal.license.VersionPrinter;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.StringResource;
import org.flywaydb.core.internal.sqlscript.SqlStatementIterator;
import org.flywaydb.database.mysql.MySQLParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Static Flyway contracts for V24's NOT_WIRED order-decision lineage schema island. */
class LongTermOrderLineageMigrationContractTest {

    private static final String MIGRATION = "V24__create_order_decision_lineage.sql";
    private static final Pattern CREATE_TRIGGER = Pattern.compile(
            "(?i)\\bCREATE\\s+TRIGGER\\s+`?([a-z0-9_]+)`?");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+TABLE\\s+`?([a-z0-9_]+)`?");
    private static final Pattern HASH_COLUMN = Pattern.compile(
            "(?im)^\\s*([a-z0-9_]*sha256)\\s+");
    private static final Pattern FORBIDDEN_COLUMN = Pattern.compile(
            "(?im)^\\s*[a-z0-9_]*(?:secret|credential|withdrawal)[a-z0-9_]*\\s+");

    private static final Set<String> TABLES = Set.of(
            "order_decision_envelope",
            "order_decision_wire_dispatch_evidence",
            "order_decision_reconciliation_evidence");
    private static final Set<String> IMMUTABILITY_TRIGGERS = Set.of(
            "trg_order_decision_envelope_no_update",
            "trg_order_decision_envelope_no_delete",
            "trg_order_wire_dispatch_no_update",
            "trg_order_wire_dispatch_no_delete",
            "trg_order_reconciliation_no_update",
            "trg_order_reconciliation_no_delete");

    @Test
    void flyway1172ParsesEveryTableAndAppendOnlyTrigger() throws IOException {
        assertThat(VersionPrinter.getVersion()).isEqualTo("11.7.2");
        List<String> statements = parseWithFlyway(read());

        assertThat(statements).hasSize(11);
        assertThat(statements).noneMatch(statement -> Pattern.compile(
                "(?im)^\\s*DELIMITER\\s+").matcher(statement).find());
        assertThat(names(statements, CREATE_TABLE)).containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(names(statements, CREATE_TRIGGER))
                .containsAll(IMMUTABILITY_TRIGGERS)
                .contains("trg_order_decision_release_bind_exact")
                .hasSize(IMMUTABILITY_TRIGGERS.size() + 1);
        assertThat(statements.stream().filter(statement ->
                CREATE_TRIGGER.matcher(statement).find())).hasSize(IMMUTABILITY_TRIGGERS.size() + 1);
    }

    @Test
    void envelopeBindsEveryRequiredDecisionFactAndProducerVariant() throws IOException {
        String envelope = compact(statementContaining(
                parseWithFlyway(read()), "CREATE TABLE order_decision_envelope"));
        String releaseBinding = compact(statementContaining(
                parseWithFlyway(read()), "CREATE TRIGGER trg_order_decision_release_bind_exact"));

        assertThat(envelope)
                .contains("market_event_idvarchar(128)")
                .contains("journal_entry_idvarchar(128)")
                .contains("archive_manifest_idvarchar(128)")
                .contains("factor_snapshot_idvarchar(128)")
                .contains("signal_idvarchar(128)")
                .contains("foreignkey(release_assignment_id,release_assignment_version,strategy_id,"
                        + "strategy_artifact_sha256,governance_subject_sha256)references"
                        + "strategy_release_assignment(assignment_id,assignment_version,"
                        + "strategy_name,artifact_sha256,governance_subject_sha256)")
                .contains("git_commit_hashvarchar(64)")
                .contains("build_artifact_sha256char(64)")
                .contains("container_image_sha256char(64)")
                .contains("configuration_sha256char(64)")
                .contains("instrument_specification_sha256char(64)")
                .contains("account_snapshot_sha256char(64)")
                .contains("risk_snapshot_sha256char(64)")
                .contains("valuation_snapshot_sha256char(64)")
                .contains("capital_policy_sha256char(64)")
                .contains("risk_policy_sha256char(64)")
                .contains("execution_policy_sha256char(64)")
                .contains("approval_sha256char(64)")
                .contains("capability_sha256char(64)")
                .contains("order_idvarchar(64)notnull")
                .contains("client_order_idvarchar(64)notnull")
                .contains("command_sha256char(64)")
                .contains("producer_kind='deterministic'andmodel_version='model_none'")
                .contains("producer_kind='agent'andmodel_version<>'model_none'")
                .contains("proposal_sha256regexp'^[0-9a-f]{64}$'")
                .contains("prompt_sha256regexp'^[0-9a-f]{64}$'")
                .contains("context_sha256regexp'^[0-9a-f]{64}$'")
                .contains("tool_trace_root_sha256regexp'^[0-9a-f]{64}$'")
                .contains("proposal_sha256isnotnull")
                .contains("prompt_sha256isnotnull")
                .contains("context_sha256isnotnull")
                .contains("tool_trace_root_sha256isnotnull")
                .contains("expires_at_ms-created_at_ms<=900000")
                .contains("not_before_ms>=capability_not_before_ms")
                .contains("expires_at_ms<=capability_expires_at_ms");
        assertThat(releaseBinding)
                .contains("binaryassignment_id=binarynew.release_assignment_id")
                .contains("binarystrategy_name=binarynew.strategy_id")
                .contains("binaryartifact_sha256=binarynew.strategy_artifact_sha256")
                .contains("signalsqlstate'45000'");
    }

    @Test
    void omsBindingIsNullableForLegacyRowsButCannotSupportUnboundEvidence() throws IOException {
        List<String> statements = parseWithFlyway(read());
        String oms = compact(statementContaining(statements, "ALTER TABLE oms_order"));
        String wire = compact(statementContaining(
                statements, "CREATE TABLE order_decision_wire_dispatch_evidence"));
        String reconciliation = compact(statementContaining(
                statements, "CREATE TABLE order_decision_reconciliation_evidence"));

        assertThat(oms)
                .contains("decision_envelope_idchar(64)charactersetasciicollateascii_binnull")
                .contains("decision_envelope_sha256char(64)charactersetasciicollateascii_binnull")
                .contains("uniquekeyuk_oms_order_decision_once(decision_envelope_id)")
                .contains("foreignkey(decision_envelope_id,decision_envelope_sha256,order_id,"
                        + "client_order_id,command_sha256)referencesorder_decision_envelope("
                        + "envelope_id,envelope_sha256,order_id,client_order_id,command_sha256)")
                .contains("decision_envelope_idisnullanddecision_envelope_sha256isnull")
                .contains("decision_envelope_idisnotnull")
                .contains("decision_envelope_sha256isnotnull")
                .contains("command_sha256isnotnull");
        assertThat(wire)
                .contains("foreignkey(order_id,envelope_id,envelope_sha256,command_sha256)"
                        + "referencesoms_order(order_id,decision_envelope_id,"
                        + "decision_envelope_sha256,command_sha256)")
                .contains("wire_result_unknown");
        assertThat(reconciliation)
                .contains("foreignkey(order_id,envelope_id,envelope_sha256,command_sha256)"
                        + "referencesoms_order(order_id,decision_envelope_id,"
                        + "decision_envelope_sha256,command_sha256)")
                .contains("oms_snapshot_sha256")
                .contains("venue_orders_snapshot_sha256")
                .contains("venue_fills_snapshot_sha256")
                .contains("balance_snapshot_sha256")
                .contains("position_snapshot_sha256")
                .contains("ledger_snapshot_sha256");
    }

    @Test
    void everyNewTableDigestIsLowercaseAndSchemaContainsNoAuthorityMaterial() throws IOException {
        String script = read();
        assertThat(FORBIDDEN_COLUMN.matcher(script).find()).isFalse();
        assertThat(script).doesNotContain("[0-9A-F]")
                .contains("NOT_WIRED")
                .contains("legacy OMS row with no exact envelope binding is")
                .doesNotContain("UPDATE oms_order SET")
                .doesNotContain("INSERT INTO order_decision_");

        for (String statement : parseWithFlyway(script)) {
            if (!CREATE_TABLE.matcher(statement).find()) continue;
            Matcher columns = HASH_COLUMN.matcher(statement);
            while (columns.find()) {
                String column = columns.group(1).toLowerCase(Locale.ROOT);
                String required = column.equals("strategy_artifact_sha256")
                        ? "regexp_like(" + column + ",'^[0-9a-f]{64}$','c')"
                        : column + "regexp'^[0-9a-f]{64}$'";
                assertThat(compact(statement)).as("lowercase digest constraint for %s", column)
                        .contains(required);
            }
        }
    }

    private String read() throws IOException {
        return new ClassPathResource("db/migration/" + MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> parseWithFlyway(String script) {
        ClassicConfiguration configuration = new ClassicConfiguration();
        configuration.setPlaceholderReplacement(false);
        MySQLParser parser = new MySQLParser(configuration, new ParsingContext());
        List<String> statements = new ArrayList<>();
        try (SqlStatementIterator iterator = parser.parse(new StringResource(script))) {
            iterator.forEachRemaining(statement -> statements.add(statement.getSql()));
        }
        return statements;
    }

    private Set<String> names(List<String> fragments, Pattern pattern) {
        Set<String> names = new LinkedHashSet<>();
        for (String fragment : fragments) {
            Matcher matcher = pattern.matcher(fragment);
            while (matcher.find()) names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private String statementContaining(List<String> statements, String needle) {
        return statements.stream()
                .filter(statement -> statement.toLowerCase(Locale.ROOT)
                        .contains(needle.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing Flyway statement: " + needle));
    }

    private String compact(String sql) {
        return sql.toLowerCase(Locale.ROOT).replace("`", "").replaceAll("\\s+", "");
    }
}

/** Optional V23-to-V24 upgrade proof; skipped automatically when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
class LongTermOrderLineageMigrationMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = MySqlTestImage.container()
            .withDatabaseName("crypto_order_lineage")
            .withUsername("crypto_lineage")
            .withPassword("crypto_lineage");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("23")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("""
                INSERT INTO oms_order
                    (order_id, client_order_id, strategy_id, exchange, market_type, symbol,
                     trade_side, requested_side, position_side, reduce_only, order_type,
                     quantity, filled_quantity, status, created_at_ms)
                VALUES ('legacy-order-1', 'legacy-client-1', 'legacy-strategy',
                        'BINANCE', 'PERPETUAL', 'BTCUSDT', 'BUY', 'LONG', 'LONG',
                        0, 'MARKET', 1, 0, 'CREATED', 1)
                """);
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void upgradePreservesLegacyOrderAsExplicitlyUnboundAndInstallsAppendOnlyTables() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='24'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT decision_envelope_id, decision_envelope_sha256
                FROM oms_order WHERE order_id='legacy-order-1'
                """)).containsEntry("decision_envelope_id", null)
                .containsEntry("decision_envelope_sha256", null);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'order_decision_%'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_order_%'
                """, Integer.class)).isEqualTo(7);
    }
}
