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

/** MySQL parser and invariant checks for the short-term P0 hardening migration. */
class ShortTermHardeningMigrationContractTest {

    @Test
    void v26EnforcesSingleActiveConfigAndAppendOnlySignals() throws Exception {
        String script = new ClassPathResource(
                "db/migration/V26__harden_config_version_and_signal_delivery.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        List<String> statements = parseWithFlyway(script);
        assertThat(statements).hasSize(3);

        String compact = compact(String.join("\n", statements));
        assertThat(compact)
                .contains("generatedalwaysas(casewhenstatus='active'")
                .contains("uniquekeyuk_admin_config_single_active(active_scope)")
                .contains("check(checksumisnotnullandchecksumregexp'^[0-9a-f]{64}$')")
                .contains("check(statusin('draft','validated','published','active','rolled_back','archived'))")
                .contains("createtriggertrg_signal_event_no_update")
                .contains("createtriggertrg_signal_event_no_delete")
                .contains("signal_eventisappend-only");
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

    private String compact(String sql) {
        return sql.toLowerCase(Locale.ROOT)
                .replace("`", "")
                .replaceAll("\\s+", "");
    }
}
