package com.tj.crypto.research.experiment;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Static guard for the V16 compound-trigger/Flyway delimiter contract. */
class ExperimentRegistryMigrationContractTest {

    @Test
    void v16SerializesDefinitionWritesAndRejectsThemAfterFamilySeal() throws IOException {
        String migration = new ClassPathResource(
                "db/migration/V16__create_research_experiment_registry.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("DELIMITER $$")
                .contains("END$$")
                .contains("DELIMITER ;")
                .contains("trg_research_experiment_artifact_reject_after_seal")
                .contains("BEFORE INSERT ON research_experiment_artifact")
                .contains("trg_research_experiment_trial_reject_after_seal")
                .contains("BEFORE INSERT ON research_experiment_trial")
                .contains("trg_research_experiment_event_serialize_definition_seal")
                .contains("BEFORE INSERT ON research_experiment_event")
                .contains("trg_research_experiment_chain_forward_only")
                .contains("BEFORE UPDATE ON research_experiment_chain_head")
                .contains("NEW.last_event_sequence<>OLD.last_event_sequence+1")
                .contains("event_sequence=NEW.last_event_sequence")
                .contains("event_sha256=NEW.last_event_sha256")
                .contains("previous_event_sha256=OLD.last_event_sha256")
                .contains("FROM research_experiment_registration")
                .contains("FOR UPDATE")
                .contains("event_type = 'FAMILY_REGISTERED'")
                .contains("artifact definitions are sealed")
                .contains("trial definitions are sealed");

        assertThat(count(migration, "DELIMITER $$")).isEqualTo(1);
        assertThat(count(migration, "END$$")).isEqualTo(4);
        assertThat(count(migration, "DELIMITER ;")).isEqualTo(1);
        assertThat(migration.indexOf("CREATE TABLE research_experiment_event"))
                .isLessThan(migration.indexOf(
                        "trg_research_experiment_artifact_reject_after_seal"));

        List<String> statements = parseFlywayDelimitedStatements(migration);
        assertThat(statements).filteredOn(statement -> statement.contains(
                        "trg_research_experiment_artifact_reject_after_seal"))
                .singleElement().asString().contains("BEGIN", "END");
        assertThat(statements).filteredOn(statement -> statement.contains(
                        "trg_research_experiment_trial_reject_after_seal"))
                .singleElement().asString().contains("BEGIN", "END");
        assertThat(statements).filteredOn(statement -> statement.contains(
                        "trg_research_experiment_event_serialize_definition_seal"))
                .singleElement().asString().contains("BEGIN", "END");
        assertThat(statements).filteredOn(statement -> statement.contains(
                        "trg_research_experiment_chain_forward_only"))
                .singleElement().asString().contains("BEGIN", "END");
    }

    private List<String> parseFlywayDelimitedStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String delimiter = ";";
        for (String line : script.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            if (trimmed.startsWith("DELIMITER ")) {
                if (!current.toString().isBlank()) {
                    throw new IllegalArgumentException(
                            "DELIMITER directive occurred inside an SQL statement");
                }
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            current.append(line).append('\n');
            String candidate = current.toString().stripTrailing();
            if (candidate.endsWith(delimiter)) {
                statements.add(candidate.substring(0,
                        candidate.length() - delimiter.length()).strip());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            throw new IllegalArgumentException("unterminated SQL statement in V16 migration");
        }
        return statements;
    }

    private int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
