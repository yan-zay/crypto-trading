package com.tj.crypto.observability.alert;

import com.tj.crypto.marketdata.connector.ConnectorHealth;
import com.tj.crypto.observability.MetricsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceTest {

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = new AlertService();
    }

    // ========== 规则管理 ==========

    @Test
    @DisplayName("添加规则后可通过 getActiveRules 查到")
    void shouldAddRuleAndRetrieve() {
        AlertRule rule = new AlertRule("low-throughput", "EVENT_THROUGHPUT_DROP", 10,
                AlertRule.Severity.WARNING, true);

        alertService.addRule(rule);

        List<AlertRule> rules = alertService.getActiveRules();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).name()).isEqualTo("low-throughput");
    }

    @Test
    @DisplayName("同名规则覆盖")
    void shouldOverwriteSameNameRule() {
        AlertRule rule1 = new AlertRule("r1", "EVENT_THROUGHPUT_DROP", 10,
                AlertRule.Severity.WARNING, true);
        AlertRule rule2 = new AlertRule("r1", "HIGH_MEMORY", 90,
                AlertRule.Severity.CRITICAL, true);

        alertService.addRule(rule1);
        alertService.addRule(rule2);

        assertThat(alertService.getActiveRules()).hasSize(1);
        assertThat(alertService.getActiveRules().get(0).condition()).isEqualTo("HIGH_MEMORY");
    }

    @Test
    @DisplayName("移除存在的规则返回 true")
    void shouldRemoveExistingRule() {
        alertService.addRule(new AlertRule("r1", "EVENT_THROUGHPUT_DROP", 10,
                AlertRule.Severity.WARNING, true));

        boolean removed = alertService.removeRule("r1");

        assertThat(removed).isTrue();
        assertThat(alertService.getActiveRules()).isEmpty();
    }

    @Test
    @DisplayName("移除不存在的规则返回 false")
    void shouldReturnFalseWhenRemovingNonExistentRule() {
        assertThat(alertService.removeRule("no-such-rule")).isFalse();
    }

    // ========== 事件吞吐下降 ==========

    @Test
    @DisplayName("EVENT_THROUGHPUT_DROP: 事件数低于阈值时触发告警")
    void shouldAlertWhenThroughputBelowThreshold() {
        alertService.addRule(new AlertRule("low-throughput", "EVENT_THROUGHPUT_DROP", 100,
                AlertRule.Severity.WARNING, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                5, 0, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 0, 50.0, 0.0
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).ruleName()).isEqualTo("low-throughput");
        assertThat(alerts.get(0).severity()).isEqualTo(AlertRule.Severity.WARNING);
        assertThat(alerts.get(0).resolved()).isFalse();
        assertThat(alerts.get(0).message()).contains("below threshold");
    }

    @Test
    @DisplayName("EVENT_THROUGHPUT_DROP: 事件数高于阈值时不触发")
    void shouldNotAlertWhenThroughputAboveThreshold() {
        alertService.addRule(new AlertRule("low-throughput", "EVENT_THROUGHPUT_DROP", 10,
                AlertRule.Severity.WARNING, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                50, 30, 20, 10, 0, Map.of(),
                0, 0, 0, 0, 0, 50.0, 0.0
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);
        assertThat(alerts).isEmpty();
    }

    // ========== 连接断开 ==========

    @Test
    @DisplayName("DISCONNECTED: 有断开连接器时触发告警")
    void shouldAlertWhenConnectorDisconnected() {
        alertService.addRule(new AlertRule("conn-down", "DISCONNECTED", 1,
                AlertRule.Severity.CRITICAL, true));

        Map<String, ConnectorHealth> healthMap = Map.of(
                "binance", new ConnectorHealth(false, 0, 0, 3, "Connection refused"),
                "coinglass", new ConnectorHealth(true, 1000, 500, 0, null)
        );
        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, healthMap,
                0, 0, 0, 0, 0, 50.0, 0.0
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).severity()).isEqualTo(AlertRule.Severity.CRITICAL);
        assertThat(alerts.get(0).message()).contains("disconnected");
    }

    @Test
    @DisplayName("DISCONNECTED: 所有连接器正常时不触发")
    void shouldNotAlertWhenAllConnectorsHealthy() {
        alertService.addRule(new AlertRule("conn-down", "DISCONNECTED", 1,
                AlertRule.Severity.CRITICAL, true));

        Map<String, ConnectorHealth> healthMap = Map.of(
                "binance", new ConnectorHealth(true, 1000, 500, 0, null)
        );
        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, healthMap,
                0, 0, 0, 0, 0, 50.0, 0.0
        );

        assertThat(alertService.checkAndAlert(snapshot)).isEmpty();
    }

    // ========== 内存使用过高 ==========

    @Test
    @DisplayName("HIGH_MEMORY: 内存使用率超过阈值时触发")
    void shouldAlertWhenMemoryHigh() {
        alertService.addRule(new AlertRule("mem-high", "HIGH_MEMORY", 85.0,
                AlertRule.Severity.WARNING, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 0, 92.5, 0.0
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).message()).contains("92.5%");
    }

    @Test
    @DisplayName("HIGH_MEMORY: 内存使用率低于阈值时不触发")
    void shouldNotAlertWhenMemoryNormal() {
        alertService.addRule(new AlertRule("mem-high", "HIGH_MEMORY", 85.0,
                AlertRule.Severity.WARNING, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 0, 60.0, 0.0
        );

        assertThat(alertService.checkAndAlert(snapshot)).isEmpty();
    }

    // ========== 错误率过高 ==========

    @Test
    @DisplayName("HIGH_ERROR_RATE: 错误率超过阈值时触发")
    void shouldAlertWhenErrorRateHigh() {
        alertService.addRule(new AlertRule("errors", "HIGH_ERROR_RATE", 5.0,
                AlertRule.Severity.CRITICAL, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 0, 50.0, 12.3
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).message()).contains("12.30%");
    }

    // ========== 事件延迟 ==========

    @Test
    @DisplayName("HIGH_EVENT_LATENCY: P99 延迟超过阈值时触发")
    void shouldAlertWhenEventLatencyHigh() {
        alertService.addRule(new AlertRule("slow-events", "HIGH_EVENT_LATENCY", 100.0,
                AlertRule.Severity.WARNING, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, Map.of(),
                50, 250.0, 0, 0, 0, 50.0, 0.0
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).message()).contains("250.0ms");
    }

    // ========== 策略执行延迟 ==========

    @Test
    @DisplayName("HIGH_STRATEGY_LATENCY: 策略 P99 超过阈值时触发")
    void shouldAlertWhenStrategyLatencyHigh() {
        alertService.addRule(new AlertRule("slow-strategy", "HIGH_STRATEGY_LATENCY", 200.0,
                AlertRule.Severity.WARNING, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, Map.of(),
                0, 0, 100, 500.0, 0, 50.0, 0.0
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).message()).contains("500.0ms");
    }

    // ========== 队列深度 ==========

    @Test
    @DisplayName("QUEUE_DEPTH: 队列深度超过阈值时触发")
    void shouldAlertWhenQueueDepthHigh() {
        alertService.addRule(new AlertRule("queue-full", "QUEUE_DEPTH", 1000,
                AlertRule.Severity.WARNING, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 1500, 50.0, 0.0
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).message()).contains("1500");
    }

    // ========== 禁用规则 ==========

    @Test
    @DisplayName("禁用的规则不触发告警")
    void shouldSkipDisabledRules() {
        alertService.addRule(new AlertRule("disabled", "EVENT_THROUGHPUT_DROP", 999999,
                AlertRule.Severity.CRITICAL, false));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 0, 50.0, 0.0
        );

        assertThat(alertService.checkAndAlert(snapshot)).isEmpty();
    }

    // ========== 多规则同时触发 ==========

    @Test
    @DisplayName("多个规则同时触发时返回所有告警")
    void shouldReturnAllTriggeredAlerts() {
        alertService.addRule(new AlertRule("throughput", "EVENT_THROUGHPUT_DROP", 100,
                AlertRule.Severity.WARNING, true));
        alertService.addRule(new AlertRule("memory", "HIGH_MEMORY", 80.0,
                AlertRule.Severity.CRITICAL, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                10, 5, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 0, 95.0, 0.0
        );

        List<AlertEvent> alerts = alertService.checkAndAlert(snapshot);

        assertThat(alerts).hasSize(2);
        assertThat(alerts).extracting(AlertEvent::ruleName)
                .containsExactlyInAnyOrder("throughput", "memory");
    }

    // ========== 告警历史 ==========

    @Test
    @DisplayName("告警事件记录在历史中")
    void shouldRecordAlertHistory() {
        alertService.addRule(new AlertRule("r1", "EVENT_THROUGHPUT_DROP", 100,
                AlertRule.Severity.WARNING, true));

        MetricsSnapshot lowSnapshot = new MetricsSnapshot(
                5, 0, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 0, 50.0, 0.0
        );
        alertService.checkAndAlert(lowSnapshot);
        alertService.checkAndAlert(lowSnapshot);

        List<AlertEvent> history = alertService.getAlertHistory();
        assertThat(history).hasSize(2);
    }

    // ========== 未知条件类型 ==========

    @Test
    @DisplayName("未知条件类型不触发告警")
    void shouldIgnoreUnknownConditionType() {
        alertService.addRule(new AlertRule("unknown", "UNKNOWN_CONDITION", 0,
                AlertRule.Severity.INFO, true));

        MetricsSnapshot snapshot = new MetricsSnapshot(
                0, 0, 0, 0, 0, Map.of(),
                0, 0, 0, 0, 0, 50.0, 0.0
        );

        assertThat(alertService.checkAndAlert(snapshot)).isEmpty();
    }
}
