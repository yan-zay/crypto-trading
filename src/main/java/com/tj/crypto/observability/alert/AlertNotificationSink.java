package com.tj.crypto.observability.alert;

/** External or local destination for an alert state transition. */
public interface AlertNotificationSink {
    void send(AlertEvent event);
}
