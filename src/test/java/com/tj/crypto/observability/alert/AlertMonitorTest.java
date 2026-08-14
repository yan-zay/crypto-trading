package com.tj.crypto.observability.alert;

import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.observability.MetricsSnapshot;
import com.tj.crypto.observability.SystemMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertMonitorTest {
    @Test
    void emitsOnlyTriggerAndRecoveryTransitions() {
        AlertService service = new AlertService();
        SystemMetrics metrics = mock(SystemMetrics.class);
        List<AlertEvent> delivered = new ArrayList<>();
        AlertMonitor monitor = new AlertMonitor(service, metrics, List.of(delivered::add),
                new SimpleMeterRegistry());
        MetricsSnapshot down = snapshot(false);
        MetricsSnapshot up = snapshot(true);
        when(metrics.snapshot()).thenReturn(down, down, up);

        monitor.check();
        monitor.check();
        monitor.check();

        assertThat(delivered).filteredOn(e -> e.ruleName().equals("connector-disconnected"))
                .hasSize(2)
                .extracting(AlertEvent::resolved).containsExactly(false, true);
    }

    @Test
    void recordsDeliveryFailureWithoutBreakingOtherSinks() {
        AlertService service = new AlertService();
        SystemMetrics metrics = mock(SystemMetrics.class);
        when(metrics.snapshot()).thenReturn(snapshot(false));
        List<AlertEvent> delivered = new ArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertMonitor monitor = new AlertMonitor(service, metrics, List.of(
                event -> { throw new IllegalStateException("offline"); }, delivered::add), registry);

        monitor.check();

        assertThat(delivered).isNotEmpty();
        assertThat(registry.counter("trading.alert.delivery.failures").count()).isGreaterThan(0);
    }

    private MetricsSnapshot snapshot(boolean connected) {
        return new MetricsSnapshot(10, 0, 0, 0, 0,
                Map.of("binance", new ConnectorHealth(connected, 10, 1, 0, null)),
                0, 0, 0, 0, 0, 50, 0);
    }
}
