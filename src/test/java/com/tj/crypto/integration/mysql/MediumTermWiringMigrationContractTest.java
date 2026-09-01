package com.tj.crypto.integration.mysql;

import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.StringResource;
import org.flywaydb.core.internal.sqlscript.SqlStatementIterator;
import org.flywaydb.database.mysql.MySQLParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MediumTermWiringMigrationContractTest {

    @Test
    void v27BindsBacktestsToRegistryAndRoutesLifecycleSignals() throws Exception {
        String script = new ClassPathResource(
                "db/migration/V27__wire_medium_term_research_and_signal_lineage.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> statements = parse(script);
        assertThat(statements).hasSize(10);
        String compact = compact(String.join("\n", statements));
        assertThat(compact)
                .contains("uk_research_experiment_exact(registration_id,registration_sha256)")
                .contains("lifecycle_stagevarchar(20)notnulldefault'legacy'")
                .contains("delivery_targetvarchar(20)notnulldefault'legacy'")
                .contains("foreignkey(research_registration_id,research_trial_id)")
                .contains("referencesresearch_experiment_trial(registration_id,trial_id)")
                .contains("model_output_sha256char(64)charactersetasciicollateascii_binnull")
                .contains("eval_report_sha256char(64)charactersetasciicollateascii_binnull")
                .contains("createtableagent_paper_shadow_proposal_use")
                .contains("check(live_execution_authorized=b'0')")
                .contains("createtriggertrg_agent_proposal_use_no_update")
                .contains("createtriggertrg_agent_proposal_use_no_delete")
                .contains("createtriggertrg_signal_event_require_release_lineage")
                .contains("new.lifecycle_stage='shadow'andnew.delivery_target<>'shadow'")
                .contains("new.lifecycle_stagein('canary','promoted')andnew.delivery_target<>'paper'");
    }

    private List<String> parse(String script) {
        ClassicConfiguration configuration = new ClassicConfiguration();
        configuration.setPlaceholderReplacement(false);
        MySQLParser parser = new MySQLParser(configuration, new ParsingContext());
        List<String> statements = new ArrayList<>();
        try (SqlStatementIterator iterator = parser.parse(new StringResource(script))) {
            iterator.forEachRemaining(statement -> statements.add(statement.getSql()));
        }
        return statements;
    }

    private String compact(String sql) {
        return sql.toLowerCase(Locale.ROOT).replace("`", "").replaceAll("\\s+", "");
    }
}
