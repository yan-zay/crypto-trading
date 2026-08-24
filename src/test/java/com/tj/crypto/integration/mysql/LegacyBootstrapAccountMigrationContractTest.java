package com.tj.crypto.integration.mysql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyBootstrapAccountMigrationContractTest {

    @Test
    void v25DisablesOnlyUnrotatedLegacyBootstrapCredentials() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/"
                        + "V25__disable_legacy_bootstrap_admin_users.sql"))
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("update admin_user")
                .contains("set enabled = 0")
                .contains("where enabled = 1")
                .contains("username = 'admin'")
                .contains("username = 'viewer'")
                .contains("password_hash = '$2a$10$")
                .doesNotContain("insert into admin_user")
                .doesNotContain("delete from admin_user");
    }
}
