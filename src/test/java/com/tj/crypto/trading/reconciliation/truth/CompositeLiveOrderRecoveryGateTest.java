package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeLiveOrderRecoveryGateTest {
    @Test
    void requiresBothInternalRecoveryAndVenueTruth() {
        CompositeLiveOrderRecoveryGate gate = new CompositeLiveOrderRecoveryGate();

        gate.markReady();

        assertThat(gate.state()).isEqualTo(LiveOrderRecoveryGate.State.PENDING);
        assertThatThrownBy(gate::requireReadyForOpeningRisk)
                .hasMessageContaining("venueTruth=PENDING");

        gate.markTruthReady();
        assertThat(gate.isReady()).isTrue();
        assertThatCode(gate::requireReadyForOpeningRisk).doesNotThrowAnyException();

        gate.markBlocked();
        assertThat(gate.state()).isEqualTo(LiveOrderRecoveryGate.State.BLOCKED);
        gate.markReady();
        gate.markTruthBlocked();
        assertThat(gate.state()).isEqualTo(LiveOrderRecoveryGate.State.BLOCKED);
    }
}
