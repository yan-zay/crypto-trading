package com.tj.crypto.strategy.lifecycle;

import java.util.StringJoiner;

final class LifecycleAuditHasher {
    static final String GENESIS = "0".repeat(64);

    private LifecycleAuditHasher() {}

    static String hash(StrategyLifecycleEvent event) {
        String canonical = new StringJoiner("\n")
                .add(nullToEmpty(event.previousEventSha256()))
                .add(event.eventId())
                .add(event.strategyName())
                .add(nullToEmpty(event.artifactSha256()))
                .add(Long.toString(event.sequenceNo()))
                .add(Long.toString(event.reviewCycle()))
                .add(enumText(event.fromStage()))
                .add(enumText(event.toStage()))
                .add(event.action().name())
                .add(Boolean.toString(event.accepted()))
                .add(event.reason().name())
                .add(nullToEmpty(event.detail()))
                .add(event.operator())
                .add(nullToEmpty(event.evidenceSetSha256()))
                .add(Long.toString(event.occurredAt().toEpochMilli()))
                .toString();
        return Hashes.sha256(canonical);
    }

    private static String enumText(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
