package com.tj.crypto.observability.slo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SloObservationWindowTest {
    private static final long HOUR = 3_600_000;

    @Test
    void returnsNoDataInsteadOfFalseHealthyState() {
        SloCurrentStatus status = new SloObservationWindow()
                .snapshot(SloName.OUTBOX_DELIVERY, 10_000, HOUR);

        assertThat(status.state()).isEqualTo("NO_DATA");
        assertThat(status.actualValue()).isNull();
        assertThat(status.compliant()).isFalse();
    }

    @Test
    void calculatesRatioLatencyAndConsumedErrorBudget() {
        SloObservationWindow window = new SloObservationWindow();
        long now = 4_000_000;
        for (int i = 0; i < 999; i++) window.record(now, true, 10, HOUR);
        window.record(now, false, 100, HOUR);

        SloCurrentStatus status = window.snapshot(SloName.OUTBOX_DELIVERY, now, HOUR);

        assertThat(status.actualValue()).isEqualByComparingTo("0.99900000");
        assertThat(status.compliant()).isTrue();
        assertThat(status.errorBudgetRemainingPct()).isEqualByComparingTo("0.00000000");
        assertThat(status.maxLatencyMs()).isEqualTo(100);
    }

    @Test
    void excludesExpiredBuckets() {
        SloObservationWindow window = new SloObservationWindow();
        long now = 10_000_000;
        window.record(now - HOUR - 120_000, false, 20, HOUR);
        window.record(now, true, 5, HOUR);

        SloCurrentStatus status = window.snapshot(SloName.RECONCILIATION_CONSISTENCY, now, HOUR);

        assertThat(status.sampleCount()).isEqualTo(1);
        assertThat(status.compliant()).isTrue();
    }

    @Test
    void restoresAggregatedSnapshotAfterProcessRestart() {
        SloObservationWindow window = new SloObservationWindow();
        long now = 20_000_000;

        window.restore(now - 10_000, 1000, 999, 12_000, 80, HOUR);

        SloCurrentStatus status = window.snapshot(SloName.PAPER_ORDER_AVAILABILITY, now, HOUR);
        assertThat(status.sampleCount()).isEqualTo(1000);
        assertThat(status.actualValue()).isEqualByComparingTo("0.99900000");
        assertThat(status.averageLatencyMs()).isEqualTo(12);
        assertThat(status.maxLatencyMs()).isEqualTo(80);
    }
}
