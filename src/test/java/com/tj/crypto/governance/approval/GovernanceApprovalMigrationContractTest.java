package com.tj.crypto.governance.approval;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceApprovalMigrationContractTest {

    @Test
    void v20NormalizesGloballyUniqueReplayReservationsWithoutChangingParentKey()
            throws IOException, NoSuchMethodException {
        String migration = new ClassPathResource(
                "db/migration/V20__create_governance_control_evidence.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE governance_approval_consumption")
                .contains("request_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY")
                .contains("CREATE TABLE governance_approval_mfa_reservation")
                .contains("PRIMARY KEY (replay_reservation_id)")
                .contains("uk_governance_approval_mfa_request_approver (request_id,approver)")
                .contains("FOREIGN KEY (request_id) REFERENCES governance_approval_consumption(request_id)")
                .contains("trg_governance_approval_mfa_reservation_no_update")
                .contains("trg_governance_approval_mfa_reservation_no_delete")
                .doesNotContain("mfa_replay_reservation_ids");
        assertThat(DatabaseApprovalConsumptionStore.class
                .getMethod("tryConsume", ApprovalConsumption.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
