package com.tj.crypto.integration.mysql;

import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.internal.license.VersionPrinter;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.StringResource;
import org.flywaydb.core.internal.sqlscript.SqlStatementIterator;
import org.flywaydb.database.mysql.MySQLParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static database contracts for the medium-term migrations.
 *
 * <p>This test deliberately uses Flyway's real MySQL parser instead of a local SQL splitter.
 * A real MySQL instance remains the authority for execution semantics, but parser regressions and
 * accidental weakening of the durable lineage constraints must fail the default Java 17 suite.</p>
 */
class MediumTermMigrationContractTest {

    private static final List<String> MIGRATIONS = List.of(
            "V15__create_immutable_market_data_catalog.sql",
            "V16__create_research_experiment_registry.sql",
            "V17__create_strategy_lifecycle_policy_gate.sql",
            "V19__create_agent_sandbox_audit.sql",
            "V20__create_governance_control_evidence.sql",
            "V21__bind_strategy_runtime_release.sql",
            "V22__bind_live_governance_authorization.sql");

    private static final Pattern CREATE_TRIGGER = Pattern.compile(
            "(?i)\\bCREATE\\s+TRIGGER\\s+`?([a-z0-9_]+)`?");

    private static final Map<String, Integer> EXPECTED_STATEMENT_COUNTS = Map.of(
            "V15__create_immutable_market_data_catalog.sql", 9,
            "V16__create_research_experiment_registry.sql", 18,
            "V17__create_strategy_lifecycle_policy_gate.sql", 14,
            "V19__create_agent_sandbox_audit.sql", 9,
            "V20__create_governance_control_evidence.sql", 13,
            "V21__bind_strategy_runtime_release.sql", 10,
            "V22__bind_live_governance_authorization.sql", 9);

    private static final Map<String, Set<String>> REQUIRED_IMMUTABILITY_TRIGGERS = Map.of(
            "V15__create_immutable_market_data_catalog.sql", Set.of(
                    "trg_market_data_dataset_no_update",
                    "trg_market_data_dataset_no_delete",
                    "trg_market_data_object_no_update",
                    "trg_market_data_object_no_delete",
                    "trg_market_data_manifest_no_update",
                    "trg_market_data_manifest_no_delete"),
            "V16__create_research_experiment_registry.sql", Set.of(
                    "trg_research_experiment_registration_no_update",
                    "trg_research_experiment_registration_no_delete",
                    "trg_research_experiment_chain_no_delete",
                    "trg_research_experiment_artifact_reject_after_seal",
                    "trg_research_experiment_trial_reject_after_seal",
                    "trg_research_experiment_event_serialize_definition_seal",
                    "trg_research_experiment_chain_forward_only",
                    "trg_research_experiment_artifact_no_update",
                    "trg_research_experiment_artifact_no_delete",
                    "trg_research_experiment_trial_no_update",
                    "trg_research_experiment_trial_no_delete",
                    "trg_research_experiment_event_no_update",
                    "trg_research_experiment_event_no_delete"),
            "V17__create_strategy_lifecycle_policy_gate.sql", Set.of(
                    "trg_strategy_artifact_no_update",
                    "trg_strategy_artifact_no_delete",
                    "trg_strategy_approval_no_update",
                    "trg_strategy_approval_no_delete",
                    "trg_strategy_evidence_no_update",
                    "trg_strategy_evidence_no_delete",
                    "trg_strategy_transition_no_update",
                    "trg_strategy_transition_no_delete"),
            "V19__create_agent_sandbox_audit.sql", Set.of(
                    "trg_agent_tool_audit_no_update",
                    "trg_agent_tool_audit_no_delete",
                    "trg_agent_proposal_no_update",
                    "trg_agent_proposal_no_delete",
                    "trg_agent_proposal_revocation_no_update",
                    "trg_agent_proposal_revocation_no_delete"),
            "V20__create_governance_control_evidence.sql", Set.of(
                    "trg_governance_control_evidence_no_update",
                    "trg_governance_control_evidence_no_delete",
                    "trg_governance_control_evidence_head_no_delete",
                    "trg_governance_approval_consumption_no_update",
                    "trg_governance_approval_consumption_no_delete",
                    "trg_governance_approval_mfa_reservation_no_update",
                    "trg_governance_approval_mfa_reservation_no_delete"),
            "V21__bind_strategy_runtime_release.sql", Set.of(
                    "trg_strategy_release_assignment_no_update",
                    "trg_strategy_release_assignment_no_delete",
                    "trg_strategy_release_assignment_head_no_delete",
                    "trg_signal_event_require_release_lineage"),
            "V22__bind_live_governance_authorization.sql", Set.of(
                    "trg_live_governance_evidence_bind_exact",
                    "trg_live_governance_decision_no_update",
                    "trg_live_governance_decision_no_delete",
                    "trg_live_governance_evidence_no_update",
                    "trg_live_governance_evidence_no_delete"));

    @Test
    void flyway1172MySqlParserReadsEveryStatementAndTrigger() throws IOException {
        assertThat(VersionPrinter.getVersion()).isEqualTo("11.7.2");

        for (String migration : MIGRATIONS) {
            String script = read(migration);
            List<String> statements = parseWithFlyway(script);
            Set<String> declaredTriggers = triggerNames(List.of(script));
            List<String> parsedTriggerStatements = statements.stream()
                    .filter(statement -> CREATE_TRIGGER.matcher(statement).find())
                    .toList();

            assertThat(statements).as("Flyway statements in %s", migration)
                    .hasSize(EXPECTED_STATEMENT_COUNTS.get(migration));
            assertThat(statements).as("raw DELIMITER directives leaked from %s", migration)
                    .noneMatch(statement -> Pattern.compile("(?im)^\\s*DELIMITER\\s+")
                            .matcher(statement).find());
            assertThat(parsedTriggerStatements)
                    .as("each CREATE TRIGGER in %s is one Flyway statement", migration)
                    .hasSize(declaredTriggers.size());
            assertThat(triggerNames(parsedTriggerStatements))
                    .as("Flyway did not lose or merge triggers in %s", migration)
                    .containsExactlyElementsOf(declaredTriggers)
                    .containsAll(REQUIRED_IMMUTABILITY_TRIGGERS.get(migration));
        }
    }

    @Test
    void v20MakesMfaReservationsGloballyUniqueAndAllowsExactLiveApprovalAction()
            throws IOException {
        List<String> statements = parseWithFlyway(read(
                "V20__create_governance_control_evidence.sql"));
        String approval = compact(statementContaining(
                statements, "CREATE TABLE governance_approval_consumption"));
        String reservation = compact(statementContaining(
                statements, "CREATE TABLE governance_approval_mfa_reservation"));

        assertThat(approval)
                .contains("uniquekeyuk_governance_approval_exact"
                        + "(request_id,approval_action,resource_sha256,governance_subject_sha256)")
                .contains("'live_risk_increase'");
        assertThat(reservation)
                .contains("primarykey(replay_reservation_id)")
                .contains("uniquekeyuk_governance_approval_mfa_request_approver"
                        + "(request_id,approver)")
                .contains("uniquekeyuk_governance_approval_mfa_request_ordinal"
                        + "(request_id,reservation_ordinal)")
                .contains("foreignkey(request_id)references"
                        + "governance_approval_consumption(request_id)")
                .doesNotContain("mfa_replay_reservation_ids");
    }

    @Test
    void v21ReleaseHeadCanOnlyAdvanceToTheExactNextAssignment() throws IOException {
        List<String> lifecycleStatements = parseWithFlyway(read(
                "V17__create_strategy_lifecycle_policy_gate.sql"));
        List<String> statements = parseWithFlyway(read(
                "V21__bind_strategy_runtime_release.sql"));
        String artifact = compact(statementContaining(
                lifecycleStatements, "CREATE TABLE strategy_artifact"));
        String assignment = compact(statementContaining(
                statements, "CREATE TABLE strategy_release_assignment ("));
        String headForeignKey = compact(statementContaining(
                statements, "fk_strategy_release_assignment_head_current"));
        String initial = compact(statementContaining(
                statements, "trg_strategy_release_assignment_head_initial"));
        String forwardOnly = compact(statementContaining(
                statements, "trg_strategy_release_assignment_head_forward_only"));
        String signalLineage = compact(statementContaining(
                statements, "ALTER TABLE signal_event"));
        String requireSignalLineage = compact(statementContaining(
                statements, "trg_signal_event_require_release_lineage"));

        assertThat(artifact).contains("uniquekeyuk_strategy_artifact_exact_identity"
                + "(artifact_sha256,strategy_name)");
        assertThat(assignment).contains("uniquekeyuk_strategy_release_exact_lineage"
                + "(assignment_id,assignment_version,strategy_name,"
                + "artifact_sha256,governance_subject_sha256)")
                .contains("foreignkey(artifact_sha256,strategy_name)"
                        + "referencesstrategy_artifact(artifact_sha256,strategy_name)")
                .contains("uniquekeyuk_strategy_release_signal_lineage"
                        + "(assignment_id,strategy_name,artifact_sha256,"
                        + "governance_subject_sha256)");
        assertThat(headForeignKey).contains("foreignkey(current_assignment_id,"
                + "current_assignment_version,strategy_name)referencesstrategy_release_assignment"
                + "(assignment_id,assignment_version,strategy_name)");
        assertThat(initial)
                .contains("new.current_assignment_idisnotnull")
                .contains("new.current_assignment_version<>-1");
        assertThat(forwardOnly)
                .contains("new.strategy_name<>old.strategy_name")
                .contains("new.current_assignment_version<>old.current_assignment_version+1")
                .contains("assignment_id=new.current_assignment_id")
                .contains("assignment_version=new.current_assignment_version")
                .contains("strategy_name=old.strategy_name");
        assertThat(signalLineage)
                .contains("foreignkey(release_assignment_id,strategy_name,"
                        + "strategy_artifact_sha256,governance_subject_sha256)"
                        + "referencesstrategy_release_assignment(assignment_id,strategy_name,"
                        + "artifact_sha256,governance_subject_sha256)");
        assertThat(requireSignalLineage)
                .contains("new.release_assignment_idisnull")
                .contains("new.strategy_artifact_sha256isnull")
                .contains("new.governance_subject_sha256isnull");
    }

    @Test
    void v22BindsOneImmutableDecisionToExactCoreReleaseApprovalAndOmsDecision()
            throws IOException {
        List<String> statements = parseWithFlyway(read(
                "V22__bind_live_governance_authorization.sql"));
        String decision = compact(statementContaining(
                statements, "CREATE TABLE live_governance_decision"));
        String evidenceIndex = compact(statementContaining(
                statements, "uk_governance_evidence_exact_verified_binding"));
        String evidence = compact(statementContaining(
                statements, "CREATE TABLE live_governance_decision_evidence"));
        String evidenceBinding = compact(statementContaining(
                statements, "trg_live_governance_evidence_bind_exact"));
        String oms = compact(statementContaining(statements, "ALTER TABLE oms_order"));

        assertThat(decision)
                .contains("foreignkey(release_assignment_id,release_assignment_version,"
                        + "strategy_id,strategy_artifact_sha256,governance_subject_sha256)"
                        + "referencesstrategy_release_assignment(assignment_id,assignment_version,"
                        + "strategy_name,artifact_sha256,governance_subject_sha256)")
                .contains("foreignkey(approval_request_id,approval_action,command_sha256,"
                        + "approval_governance_subject_sha256)references"
                        + "governance_approval_consumption(request_id,approval_action,"
                        + "resource_sha256,governance_subject_sha256)")
                .contains("uniquekeyuk_live_governance_approval(approval_request_id)")
                .contains("uniquekeyuk_live_governance_exact_evidence_context"
                        + "(decision_id,governance_scope,governance_environment,"
                        + "governance_subject_sha256,policy_version)")
                .contains("approval_action='live_risk_increase'")
                .contains("approval_governance_subject_sha256=governance_subject_sha256");
        assertThat(evidenceIndex).contains("uniquekey"
                + "uk_governance_evidence_exact_verified_binding(control_kind,scope,"
                + "environment,subject_sha256,policy_version,evidence_id,evidence_status)");
        assertThat(evidence)
                .contains("foreignkey(decision_id,governance_scope,governance_environment,"
                        + "governance_subject_sha256,policy_version)"
                        + "referenceslive_governance_decision(decision_id,governance_scope,"
                        + "governance_environment,governance_subject_sha256,policy_version)")
                .contains("foreignkey(control_kind,governance_scope,governance_environment,"
                        + "governance_subject_sha256,policy_version,evidence_id,evidence_status)"
                        + "referencesgovernance_control_evidence(control_kind,scope,environment,"
                        + "subject_sha256,policy_version,evidence_id,evidence_status)")
                .contains("evidence_status='verified'");
        assertThat(evidenceBinding)
                .contains("fromlive_governance_decision")
                .contains("joingovernance_control_evidencee")
                .contains("e.control_kind=new.control_kind")
                .contains("e.subject_sha256=d.governance_subject_sha256")
                .contains("e.policy_version=d.policy_version")
                .contains("e.evidence_status='verified'")
                .contains("whered.decision_id=new.decision_id")
                .contains("setnew.evidence_status='verified'");
        assertThat(oms)
                .contains("foreignkey(governance_decision_id)references"
                        + "live_governance_decision(decision_id)")
                .contains("uniquekeyuk_oms_governance_decision(governance_decision_id)")
                .contains("authorization_kind='live_risk_increase'")
                .contains("governance_decision_idisnotnull")
                .contains("governance_subject_sha256regexp'^[0-9a-f]{64}$'")
                .contains("strategy_artifact_sha256regexp'^[0-9a-f]{64}$'");
    }

    private String read(String filename) throws IOException {
        return new ClassPathResource("db/migration/" + filename)
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

    private Set<String> triggerNames(List<String> sqlFragments) {
        Set<String> names = new LinkedHashSet<>();
        for (String fragment : sqlFragments) {
            Matcher matcher = CREATE_TRIGGER.matcher(fragment);
            while (matcher.find()) {
                names.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
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
        return sql.toLowerCase(Locale.ROOT)
                .replace("`", "")
                .replaceAll("\\s+", "");
    }
}
