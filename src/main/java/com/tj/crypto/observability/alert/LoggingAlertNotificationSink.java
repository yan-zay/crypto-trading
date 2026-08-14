package com.tj.crypto.observability.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Always-available local sink; production should additionally configure a webhook sink. */
@Slf4j
@Component
public class LoggingAlertNotificationSink implements AlertNotificationSink {
    @Override
    public void send(AlertEvent event) {
        if (event.resolved()) {
            log.info("[ALERT-RESOLVED] {}: {}", event.ruleName(), event.message());
        } else {
            log.warn("[ALERT-NOTIFY] {} [{}]: {}", event.severity(), event.ruleName(), event.message());
        }
    }
}
