package com.tj.crypto.marketdata.connector.certification;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.marketdata.archive.ArchiveEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataConnectorCertificationKitTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void grantsPaperReadinessOnlyWhenEveryDataContractIsProven() {
        ConnectorCertificationDecision decision = new MarketDataConnectorCertificationKit()
                .evaluate(evidence(true, true), Set.of(ArchiveEventType.BAR), NOW);
        assertThat(decision.status()).isEqualTo(ConnectorCertificationDecision.Status.PAPER_READY);
        assertThat(decision.liveAuthorized()).isFalse();
    }

    @Test
    void rawOrAdmissionFailureKeepsConnectorResearchOnly() {
        ConnectorCertificationDecision decision = new MarketDataConnectorCertificationKit()
                .evaluate(evidence(false, false), Set.of(ArchiveEventType.BAR), NOW);
        assertThat(decision.status()).isEqualTo(ConnectorCertificationDecision.Status.RESEARCH_ONLY);
        assertThat(decision.blockers()).contains("RAW_FIRST_NOT_DURABLE", "ADMISSION_AFTER_FANOUT");
    }

    private ConnectorContractEvidence evidence(boolean raw, boolean admission) {
        return new ConnectorContractEvidence("binance-public", Exchange.BINANCE,
                Set.of(ArchiveEventType.BAR), raw, admission, true, true, true, true, true,
                "a".repeat(64), NOW.minusSeconds(1), NOW.plusSeconds(60));
    }
}
