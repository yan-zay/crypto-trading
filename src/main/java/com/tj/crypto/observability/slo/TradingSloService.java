package com.tj.crypto.observability.slo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Rolling SLO registry backed by Micrometer and durable minute snapshots. */
@Slf4j
@Service
public class TradingSloService {
    private final SloProperties properties;
    private final SloSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final MarketEventBus eventBus;
    private final Map<SloName, SloObservationWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<SloName, Timer> timers = new ConcurrentHashMap<>();

    public TradingSloService(SloProperties properties, SloSnapshotMapper snapshotMapper,
                             ObjectMapper objectMapper, MeterRegistry meterRegistry,
                             MarketEventBus eventBus) {
        this.properties = properties;
        this.snapshotMapper = snapshotMapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.eventBus = eventBus;
        Arrays.stream(SloName.values()).forEach(name -> windows.put(name, new SloObservationWindow()));
    }

    @PostConstruct
    void subscribeMarketFreshness() {
        restoreRollingWindows();
        eventBus.subscribe(BarEvent.class, event -> {
            long latency = Math.max(0, System.currentTimeMillis() - event.metadata().receivedTimestamp());
            record(SloName.MARKET_DATA_FRESHNESS,
                    latency <= properties.getMarketDataFreshnessThresholdMs(), latency);
        });
    }

    private void restoreRollingWindows() {
        long now = System.currentTimeMillis();
        for (SloName name : SloName.values()) {
            SloSnapshotDO snapshot = snapshotMapper.selectLatestNonEmpty(
                    name.name(), now - properties.getWindowMs());
            if (snapshot == null || snapshot.getSampleCount() == null
                    || snapshot.getSampleCount() <= 0) continue;
            try {
                JsonNode detail = objectMapper.readTree(snapshot.getDetailJson());
                long total = snapshot.getSampleCount();
                long success = detail.path("successCount").asLong(
                        snapshot.getActualValue() == null ? 0
                                : snapshot.getActualValue().multiply(java.math.BigDecimal.valueOf(total)).longValue());
                double averageLatency = detail.path("averageLatencyMs").asDouble(0);
                long maxLatency = detail.path("maxLatencyMs").asLong(0);
                windows.get(name).restore(snapshot.getWindowEndMs(), total, success,
                        Math.round(averageLatency * total), maxLatency, properties.getWindowMs());
                log.info("Restored SLO window: name={}, samples={}", name, total);
            } catch (JsonProcessingException | RuntimeException e) {
                log.warn("Unable to restore SLO window: name={}", name, e);
            }
        }
    }

    public void record(SloName name, boolean success, long latencyMs) {
        windows.get(name).record(System.currentTimeMillis(), success, latencyMs, properties.getWindowMs());
        counter(name, success).increment();
        timers.computeIfAbsent(name, this::timer).record(Math.max(0, latencyMs),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordPaperOrder(boolean success, long latencyMs) {
        record(SloName.PAPER_ORDER_AVAILABILITY, success, latencyMs);
        record(SloName.PAPER_ORDER_LATENCY,
                success && latencyMs <= properties.getPaperOrderLatencyThresholdMs(), latencyMs);
    }

    public List<SloCurrentStatus> current() {
        long now = System.currentTimeMillis();
        return Arrays.stream(SloName.values())
                .map(name -> windows.get(name).snapshot(name, now, properties.getWindowMs()))
                .toList();
    }

    public List<SloSnapshotDO> history(String name, int requestedLimit) {
        if (name != null && !name.isBlank()) SloName.valueOf(name);
        return snapshotMapper.selectRecent(name == null || name.isBlank() ? null : name,
                Math.max(1, Math.min(requestedLimit, 1000)));
    }

    @Scheduled(fixedDelayString = "${crypto.slo.snapshot-interval-ms:60000}")
    public void persistSnapshots() {
        for (SloCurrentStatus status : current()) {
            SloSnapshotDO snapshot = new SloSnapshotDO();
            snapshot.setSnapshotId(UUID.randomUUID().toString());
            snapshot.setSloName(status.name());
            snapshot.setWindowStartMs(status.windowStartMs());
            snapshot.setWindowEndMs(status.windowEndMs());
            snapshot.setTargetValue(status.targetValue());
            snapshot.setActualValue(status.actualValue());
            snapshot.setCompliant(status.compliant());
            snapshot.setErrorBudgetRemainingPct(status.errorBudgetRemainingPct());
            snapshot.setSampleCount(status.sampleCount());
            snapshot.setDetailJson(json(Map.of(
                    "successCount", status.successCount(),
                    "failureCount", status.failureCount(),
                    "averageLatencyMs", status.averageLatencyMs(),
                    "maxLatencyMs", status.maxLatencyMs(),
                    "state", status.state())));
            snapshotMapper.upsert(snapshot);
        }
    }

    private Counter counter(SloName name, boolean success) {
        String key = name.name() + ':' + success;
        return counters.computeIfAbsent(key, ignored -> Counter.builder("crypto_slo_observations_total")
                .description("Trading SLO observations")
                .tag("slo", name.name())
                .tag("outcome", success ? "success" : "failure")
                .register(meterRegistry));
    }

    private Timer timer(SloName name) {
        return Timer.builder("crypto_slo_observation_latency")
                .description("Latency attached to trading SLO observations")
                .tag("slo", name.name())
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meterRegistry);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Unable to serialize SLO snapshot detail", e);
            return "{}";
        }
    }
}
