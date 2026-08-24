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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Static contracts for V23's bounded-autonomy evidence schema.
 *
 * <p>The default tests use Flyway's actual MySQL parser. The optional container proof below is
 * skipped when Docker is unavailable; neither path represents owner-venue, KMS, legal, soak, or
 * live-canary evidence.</p>
 */
class LongTermAutonomyMigrationContractTest {

    private static final String MIGRATION =
            "V23__create_bounded_autonomy_control_plane.sql";
    private static final Pattern CREATE_TRIGGER = Pattern.compile(
            "(?i)\\bCREATE\\s+TRIGGER\\s+`?([a-z0-9_]+)`?");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+TABLE\\s+`?([a-z0-9_]+)`?");
    private static final Pattern HASH_COLUMN = Pattern.compile(
            "(?im)^\\s*([a-z0-9_]*sha256)\\s+");
    private static final Pattern FORBIDDEN_COLUMN = Pattern.compile(
            "(?im)^\\s*[a-z0-9_]*(?:secret|credential|withdrawal)[a-z0-9_]*\\s+");
    private static final Pattern MUTABLE_STATUS_COLUMN = Pattern.compile(
            "(?im)^\\s*[a-z0-9_]*status[a-z0-9_]*\\s+");

    private static final Set<String> TABLES = Set.of(
            "autonomy_account_scope",
            "autonomy_capital_policy_version",
            "autonomy_risk_policy_version",
            "autonomy_l3_proposal",
            "autonomy_human_approval_event",
            "autonomy_replay_reservation",
            "autonomy_l4_capability",
            "autonomy_l4_capability_terminal_event",
            "autonomy_canary_decision_event",
            "autonomy_watchdog_event",
            "autonomy_human_takeover_event");

    private static final Set<String> IMMUTABILITY_TRIGGERS = Set.of(
            "trg_autonomy_scope_no_update", "trg_autonomy_scope_no_delete",
            "trg_autonomy_capital_no_update", "trg_autonomy_capital_no_delete",
            "trg_autonomy_risk_no_update", "trg_autonomy_risk_no_delete",
            "trg_autonomy_l3_no_update", "trg_autonomy_l3_no_delete",
            "trg_autonomy_human_approval_no_update",
            "trg_autonomy_human_approval_no_delete",
            "trg_autonomy_replay_no_update", "trg_autonomy_replay_no_delete",
            "trg_autonomy_l4_no_update", "trg_autonomy_l4_no_delete",
            "trg_autonomy_l4_terminal_no_update",
            "trg_autonomy_l4_terminal_no_delete",
            "trg_autonomy_canary_no_update", "trg_autonomy_canary_no_delete",
            "trg_autonomy_watchdog_no_update", "trg_autonomy_watchdog_no_delete",
            "trg_autonomy_takeover_no_update", "trg_autonomy_takeover_no_delete");

    @Test
    void flyway1172ParsesEveryTableAndTriggerWithoutDelimiterLeakage() throws IOException {
        assertThat(VersionPrinter.getVersion()).isEqualTo("11.7.2");
        String script = read();
        List<String> statements = parseWithFlyway(script);

        assertThat(statements).hasSize(35);
        assertThat(statements).noneMatch(statement -> Pattern.compile(
                "(?im)^\\s*DELIMITER\\s+").matcher(statement).find());
        assertThat(names(statements, CREATE_TABLE)).containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(names(statements, CREATE_TRIGGER))
                .containsExactlyInAnyOrderElementsOf(union(
                        IMMUTABILITY_TRIGGERS, Set.of(
                                "trg_autonomy_l4_validate_bounds",
                                "trg_autonomy_l4_terminal_validate")));
        assertThat(statements.stream()
                .filter(statement -> CREATE_TRIGGER.matcher(statement).find()))
                .hasSize(IMMUTABILITY_TRIGGERS.size() + 2);
    }

    @Test
    void everyDigestIsLowercaseSha256AndSchemaContainsNoAuthorityMaterialColumns()
            throws IOException {
        String script = read();
        assertThat(FORBIDDEN_COLUMN.matcher(script).find()).isFalse();
        assertThat(MUTABLE_STATUS_COLUMN.matcher(script).find()).isFalse();
        assertThat(script).doesNotContain("INSERT INTO autonomy_l4_capability");
        assertThat(script).contains("V23 is a NOT_WIRED schema island");
        assertThat(compact(script))
                .doesNotContain("altertableoms_order")
                .doesNotContain("referencesoms_order")
                .doesNotContain("referenceslive_risk_reservation")
                .doesNotContain("referencessignal_event");

        for (String statement : parseWithFlyway(script)) {
            if (!CREATE_TABLE.matcher(statement).find()) {
                continue;
            }
            Matcher columns = HASH_COLUMN.matcher(statement);
            while (columns.find()) {
                String column = columns.group(1).toLowerCase(Locale.ROOT);
                assertThat(compact(statement))
                        .as("lowercase SHA-256 constraint for %s", column)
                        .contains(column + "regexp'^[0-9a-f]{64}$'");
            }
        }
        assertThat(script).doesNotContain("[0-9A-F]");
    }

    @Test
    void scopesAndVersionedPoliciesAreExactAppendOnlyChains() throws IOException {
        List<String> statements = parseWithFlyway(read());
        String scope = compact(statementContaining(statements,
                "CREATE TABLE autonomy_account_scope"));
        String capital = compact(statementContaining(statements,
                "CREATE TABLE autonomy_capital_policy_version"));
        String risk = compact(statementContaining(statements,
                "CREATE TABLE autonomy_risk_policy_version"));

        assertThat(scope)
                .contains("venuevarchar(128)collateutf8mb4_binnotnull")
                .contains("account_idvarchar(192)collateutf8mb4_binnotnull")
                .contains("subaccount_idvarchar(192)collateutf8mb4_binnotnull")
                .contains("base_currencyvarchar(32)charactersetasciicollateascii_binnotnull")
                .contains("uniquekeyuk_autonomy_scope_venue_account"
                        + "(venue,environment,account_id,subaccount_id)")
                .contains("environmentin('paper','shadow','live_canary')")
                .contains("account_id<>subaccount_id");
        assertThat(capital)
                .contains("currencyvarchar(32)charactersetasciicollateascii_binnotnull")
                .contains("primarykey(capital_policy_id,policy_version)")
                .contains("uniquekeyuk_autonomy_capital_successor"
                        + "(capital_policy_id,predecessor_slot)")
                .contains("foreignkey(capital_policy_id,predecessor_version,account_scope_id)"
                        + "referencesautonomy_capital_policy_version"
                        + "(capital_policy_id,policy_version,account_scope_id)")
                .contains("predecessor_version=policy_version-1")
                .contains("max_daily_loss<=max_gross_notional");
        assertThat(risk)
                .contains("uniquekeyuk_autonomy_risk_successor"
                        + "(risk_policy_id,predecessor_slot)")
                .contains("foreignkey(capital_policy_id,capital_policy_version,"
                        + "account_scope_id,account_scope_sha256,capital_policy_sha256)"
                        + "referencesautonomy_capital_policy_version"
                        + "(capital_policy_id,policy_version,account_scope_id,"
                        + "account_scope_sha256,capital_policy_sha256)")
                .contains("kill_switch_required=1")
                .contains("single_writer_required=1");
    }

    @Test
    void l3AdviceHumanApprovalAndL4GrantAreBoundWithoutSelfExpansion()
            throws IOException {
        List<String> statements = parseWithFlyway(read());
        String proposal = compact(statementContaining(statements,
                "CREATE TABLE autonomy_l3_proposal"));
        String approval = compact(statementContaining(statements,
                "CREATE TABLE autonomy_human_approval_event"));
        String capability = compact(statementContaining(statements,
                "CREATE TABLE autonomy_l4_capability"));
        String validator = compact(statementContaining(statements,
                "CREATE TRIGGER trg_autonomy_l4_validate_bounds"));

        assertThat(proposal)
                .contains("proposal_idvarchar(96)charactersetasciicollateascii_binprimarykey")
                .contains("agent_level='l3'")
                .contains("foreignkey(strategy_artifact_sha256,strategy_name)"
                        + "referencesstrategy_artifact(artifact_sha256,strategy_name)")
                .contains("expires_at_ms-proposed_at_ms<=900000");
        assertThat(approval)
                .contains("uniquekeyuk_autonomy_human_approval_proposal(proposal_id)")
                .contains("decision='approved'")
                .contains("governance_action='capability_token_issue'")
                .contains("referencesgovernance_approval_consumption"
                        + "(request_id,approval_action,resource_sha256,"
                        + "governance_subject_sha256)");
        assertThat(capability)
                .contains("uniquekeyuk_autonomy_l4_capability_approval(approval_event_id)")
                .contains("capability_level='l4'")
                .contains("boundary_mutation_allowed=0")
                .contains("expires_at_ms-not_before_ms<=900000")
                .contains("'select_preapproved_target'")
                .contains("'adjust_within_bound'");
        assertThat(validator)
                .contains("new.max_order_notional>policy_max_order")
                .contains("new.max_gross_notional>policy_max_gross")
                .contains("new.max_daily_loss>policy_max_daily_loss")
                .contains("new.expires_at_ms>approval_expires")
                .contains("signalsqlstate'45000'");
    }

    @Test
    void capabilityHasOneTerminalEventAndReplayReservationsAreGlobal()
            throws IOException {
        List<String> statements = parseWithFlyway(read());
        String replay = compact(statementContaining(statements,
                "CREATE TABLE autonomy_replay_reservation"));
        String terminal = compact(statementContaining(statements,
                "CREATE TABLE autonomy_l4_capability_terminal_event"));
        String terminalValidator = compact(statementContaining(statements,
                "CREATE TRIGGER trg_autonomy_l4_terminal_validate"));

        assertThat(replay)
                .contains("replay_reservation_idvarchar(128)collateutf8mb4_binprimarykey")
                .contains("uniquekeyuk_autonomy_replay_resource(resource_sha256)")
                .contains("'consume_l4_capability'")
                .contains("'revoke_l4_capability'");
        assertThat(terminal)
                .contains("uniquekeyuk_autonomy_l4_terminal_capability(capability_id)")
                .contains("uniquekeyuk_autonomy_l4_terminal_action(action_sha256)")
                .contains("foreignkey(replay_reservation_id,replay_purpose,action_sha256)"
                        + "referencesautonomy_replay_reservation"
                        + "(replay_reservation_id,replay_purpose,resource_sha256)")
                .contains("event_kind='consumed'")
                .contains("event_kind='revoked'");
        assertThat(terminalValidator)
                .contains("replay_reserved>new.occurred_at_ms")
                .contains("new.occurred_at_ms<capability_not_before")
                .contains("new.occurred_at_ms>=capability_expires")
                .contains("new.occurred_at_ms<capability_issued");
    }

    @Test
    void deploymentAndSafetyChangesAreHashChainedEvents() throws IOException {
        List<String> statements = parseWithFlyway(read());
        String canary = compact(statementContaining(statements,
                "CREATE TABLE autonomy_canary_decision_event"));
        String watchdog = compact(statementContaining(statements,
                "CREATE TABLE autonomy_watchdog_event"));
        String takeover = compact(statementContaining(statements,
                "CREATE TABLE autonomy_human_takeover_event"));

        assertThat(canary)
                .contains("'champion_selected'")
                .contains("'challenger_started'")
                .contains("'canary_started'")
                .contains("'canary_promoted'")
                .contains("'canary_rolled_back'")
                .contains("canary_allocation_bpsbetween1and500")
                .contains("sequence_no=1anddecision_kind='champion_selected'")
                .contains("predecessor_sequence_no=sequence_no-1")
                .contains("replay_purpose='canary_decision'");
        assertThat(watchdog)
                .contains("'heartbeat_missed'")
                .contains("'risk_limit_breach'")
                .contains("'reconciliation_divergence'")
                .contains("control_actionin('freeze','downgrade','alert_only')")
                .doesNotContain("unfreeze")
                .contains("predecessor_sequence_no=sequence_no-1");
        assertThat(takeover)
                .contains("takeover_actionin('takeover','rollback','release')")
                .contains("sequence_no<>1ortakeover_action='takeover'")
                .contains("takeover_action='release'")
                .contains("governance_action='live_canary_enable'")
                .contains("predecessor_sequence_no=sequence_no-1");
    }

    @Test
    void everyEvidenceTableRejectsUpdateAndDelete() throws IOException {
        String script = read();
        for (String trigger : IMMUTABILITY_TRIGGERS) {
            assertThat(script).contains("CREATE TRIGGER " + trigger);
        }
        String compactScript = compact(script);
        for (String table : TABLES) {
            assertThat(compactScript)
                    .as("UPDATE immutability trigger for %s", table)
                    .contains("beforeupdateon" + table + "foreachrowsignalsqlstate'45000'");
            assertThat(compactScript)
                    .as("DELETE immutability trigger for %s", table)
                    .contains("beforedeleteon" + table + "foreachrowsignalsqlstate'45000'");
        }
        assertThat(names(parseWithFlyway(script), CREATE_TRIGGER))
                .containsAll(IMMUTABILITY_TRIGGERS);
    }

    private String read() throws IOException {
        return new ClassPathResource("db/migration/" + MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> parseWithFlyway(String script) {
        ClassicConfiguration configuration = new ClassicConfiguration();
        MySQLParser parser = new MySQLParser(configuration, new ParsingContext());
        List<String> statements = new ArrayList<>();
        try (SqlStatementIterator iterator = parser.parse(new StringResource(script))) {
            iterator.forEachRemaining(statement -> statements.add(statement.getSql()));
        }
        return statements;
    }

    private Set<String> names(List<String> fragments, Pattern pattern) {
        Set<String> result = new LinkedHashSet<>();
        for (String fragment : fragments) {
            Matcher matcher = pattern.matcher(fragment);
            while (matcher.find()) {
                result.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return result;
    }

    private String statementContaining(List<String> statements, String needle) {
        return statements.stream()
                .filter(statement -> statement.toLowerCase(Locale.ROOT)
                        .contains(needle.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing Flyway statement: " + needle));
    }

    private String compact(String sql) {
        return sql.toLowerCase(Locale.ROOT)
                .replace("`", "")
                .replaceAll("\\s+", "");
    }
}

/** Optional execution proof; disabled automatically when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
class LongTermAutonomyMigrationMySqlTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
            .withDatabaseName("crypto_long_term_autonomy")
            .withUsername("crypto_autonomy")
            .withPassword("crypto_autonomy");

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
    void freshSchemaHasNoCapabilityAndInstallsAllAppendOnlyTables() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='23'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM autonomy_l4_capability", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'autonomy_%'
                """, Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name LIKE 'autonomy_%'
                  AND (LOWER(column_name) LIKE '%secret%'
                       OR LOWER(column_name) LIKE '%credential%'
                       OR LOWER(column_name) LIKE '%withdrawal%')
                """, Integer.class)).isZero();
    }

    @Test
    void scopeIsImmutableAndReplayResourceCannotBeReservedTwice() {
        String scopeSha = "a".repeat(64);
        jdbc.update("""
                INSERT INTO autonomy_account_scope
                    (account_scope_id, account_scope_sha256, venue, environment,
                     account_id, subaccount_id, base_currency, legal_subject_sha256,
                     registered_by, registered_at_ms)
                VALUES ('scope-1', ?, 'OKX', 'LIVE_CANARY', 'owner-1', 'sub-1',
                        'USDT', ?, 'integration-test', 1)
                """, scopeSha, "b".repeat(64));

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE autonomy_account_scope SET base_currency='USD'
                WHERE account_scope_id='scope-1'
                """)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM autonomy_account_scope WHERE account_scope_id='scope-1'
                """)).hasMessageContaining("immutable");

        String resource = "c".repeat(64);
        jdbc.update("""
                INSERT INTO autonomy_replay_reservation
                    (replay_reservation_id, replay_purpose, resource_sha256,
                     reserved_by, reserved_at_ms)
                VALUES ('global-replay-1', 'WATCHDOG_EVENT', ?, 'watchdog-1', 2)
                """, resource);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO autonomy_replay_reservation
                    (replay_reservation_id, replay_purpose, resource_sha256,
                     reserved_by, reserved_at_ms)
                VALUES ('global-replay-2', 'HUMAN_TAKEOVER', ?, 'operator-1', 3)
                """, resource)).isInstanceOf(RuntimeException.class);
    }
}
