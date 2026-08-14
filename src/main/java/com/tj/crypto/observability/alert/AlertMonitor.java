package com.tj.crypto.observability.alert;

import com.tj.crypto.observability.SystemMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Periodically evaluates alert rules and emits only trigger/recovery state transitions. */
@Slf4j
@Component
public class AlertMonitor {
    private final AlertService alertService;
    private final SystemMetrics systemMetrics;
    private final List<AlertNotificationSink> sinks;
    private final Counter deliveryFailures;
    private final Map<String, AlertEvent> active = new LinkedHashMap<>();

    public AlertMonitor(AlertService alertService, SystemMetrics systemMetrics,
                        List<AlertNotificationSink> sinks, MeterRegistry meterRegistry) {
        this.alertService = alertService;
        this.systemMetrics = systemMetrics;
        this.sinks = List.copyOf(sinks);
        this.deliveryFailures = Counter.builder("trading.alert.delivery.failures")
                .description("Alert notification delivery failures").register(meterRegistry);
        installSafetyDefaults();
    }

    @Scheduled(fixedDelayString = "${crypto.alerting.check-interval-ms:60000}")
    public synchronized void check() {
        List<AlertEvent> triggered = alertService.checkAndAlert(systemMetrics.snapshot());
        Set<String> stillActive = triggered.stream().map(AlertEvent::ruleName).collect(Collectors.toSet());
        for (AlertEvent event : triggered) {
            if (active.put(event.ruleName(), event) == null) deliver(event);
        }
        for (String rule : List.copyOf(active.keySet())) {
            if (stillActive.contains(rule)) continue;
            AlertEvent previous = active.remove(rule);
            deliver(new AlertEvent(rule, previous.severity(),
                    "Resolved: " + previous.message(), System.currentTimeMillis(), true));
        }
    }

    private void deliver(AlertEvent event) {
        for (AlertNotificationSink sink : sinks) {
            try {
                sink.send(event);
            } catch (RuntimeException e) {
                deliveryFailures.increment();
                systemMetrics.recordError();
                log.error("Alert delivery failed: rule={}, sink={}",
                        event.ruleName(), sink.getClass().getSimpleName(), e);
            }
        }
    }

    private void installSafetyDefaults() {
        if (!alertService.getActiveRules().isEmpty()) return;
        alertService.addRule(new AlertRule("connector-disconnected", "DISCONNECTED", 1,
                AlertRule.Severity.CRITICAL, true));
        alertService.addRule(new AlertRule("persistence-backlog", "QUEUE_DEPTH", 8_000,
                AlertRule.Severity.CRITICAL, true));
        alertService.addRule(new AlertRule("high-error-rate", "HIGH_ERROR_RATE", 5,
                AlertRule.Severity.WARNING, true));
        alertService.addRule(new AlertRule("high-memory", "HIGH_MEMORY", 90,
                AlertRule.Severity.WARNING, true));
    }
}
