package com.tj.crypto.observability.slo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free minute buckets keep a bounded in-memory rolling SLO window. */
final class SloObservationWindow {
    private static final long BUCKET_MS = 60_000;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    void record(long timestampMs, boolean success, long latencyMs, long windowMs) {
        long bucketKey = timestampMs / BUCKET_MS;
        buckets.computeIfAbsent(bucketKey, ignored -> new Bucket())
                .record(success, Math.max(0, latencyMs));
        long oldestKey = Math.max(0, (timestampMs - windowMs - BUCKET_MS) / BUCKET_MS);
        buckets.keySet().removeIf(key -> key < oldestKey);
    }

    void restore(long timestampMs, long total, long success, long totalLatency,
                 long maxLatency, long windowMs) {
        if (total <= 0) return;
        long bucketKey = timestampMs / BUCKET_MS;
        buckets.computeIfAbsent(bucketKey, ignored -> new Bucket())
                .recordAggregate(total, success, totalLatency, maxLatency);
        long oldestKey = Math.max(0, (timestampMs - windowMs - BUCKET_MS) / BUCKET_MS);
        buckets.keySet().removeIf(key -> key < oldestKey);
    }

    SloCurrentStatus snapshot(SloName name, long now, long windowMs) {
        long start = now - windowMs;
        long firstKey = start / BUCKET_MS;
        long total = 0;
        long success = 0;
        long latency = 0;
        long maxLatency = 0;
        for (Map.Entry<Long, Bucket> entry : buckets.entrySet()) {
            if (entry.getKey() < firstKey || entry.getKey() > now / BUCKET_MS) continue;
            Bucket bucket = entry.getValue();
            total += bucket.total.sum();
            success += bucket.success.sum();
            latency += bucket.totalLatency.sum();
            maxLatency = Math.max(maxLatency, bucket.maxLatency.get());
        }
        if (total == 0) {
            return new SloCurrentStatus(name.name(), start, now, name.target(), null,
                    false, null, 0, 0, 0, 0, 0, "NO_DATA");
        }
        long failures = total - success;
        BigDecimal actual = BigDecimal.valueOf(success)
                .divide(BigDecimal.valueOf(total), 8, RoundingMode.HALF_UP);
        boolean compliant = actual.compareTo(name.target()) >= 0;
        BigDecimal errorBudget = errorBudget(name.target(), total, failures);
        return new SloCurrentStatus(name.name(), start, now, name.target(), actual,
                compliant, errorBudget, total, success, failures,
                latency / (double) total, maxLatency, compliant ? "COMPLIANT" : "BREACHED");
    }

    private BigDecimal errorBudget(BigDecimal target, long total, long failures) {
        BigDecimal allowed = BigDecimal.valueOf(total).multiply(BigDecimal.ONE.subtract(target));
        if (allowed.signum() == 0) {
            return failures == 0 ? new BigDecimal("100.00000000") : BigDecimal.ZERO;
        }
        return allowed.subtract(BigDecimal.valueOf(failures))
                .multiply(BigDecimal.valueOf(100))
                .divide(allowed, 8, RoundingMode.HALF_UP);
    }

    private static final class Bucket {
        private final LongAdder total = new LongAdder();
        private final LongAdder success = new LongAdder();
        private final LongAdder totalLatency = new LongAdder();
        private final AtomicLong maxLatency = new AtomicLong();

        private void record(boolean succeeded, long latencyMs) {
            total.increment();
            if (succeeded) success.increment();
            totalLatency.add(latencyMs);
            maxLatency.accumulateAndGet(latencyMs, Math::max);
        }

        private void recordAggregate(long observations, long successes,
                                     long latencySum, long latencyMax) {
            total.add(observations);
            success.add(Math.max(0, Math.min(observations, successes)));
            totalLatency.add(Math.max(0, latencySum));
            maxLatency.accumulateAndGet(Math.max(0, latencyMax), Math::max);
        }
    }
}
