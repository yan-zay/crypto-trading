package com.tj.crypto.trading.reconciliation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveOrderRecoveryGateTest {
    @Test
    void openingRiskIsBlockedUntilACompleteRecoveryMarksReady() {
        LiveOrderRecoveryGate gate = new LiveOrderRecoveryGate();

        assertThatThrownBy(gate::requireReadyForOpeningRisk)
                .hasMessageContaining("PENDING");
        gate.markReady();
        assertThatCode(gate::requireReadyForOpeningRisk).doesNotThrowAnyException();
        gate.markBlocked();
        assertThatThrownBy(gate::requireReadyForOpeningRisk)
                .hasMessageContaining("BLOCKED");
    }
}
