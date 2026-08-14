package com.tj.crypto.marketdata.quality;

import com.tj.crypto.common.domain.InstrumentId;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.risk.KillSwitch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stateful, fail-closed quality gate in front of live strategy dispatch.
 *
 * <p>The batch-oriented {@link DataQualityChecker} remains stateless. This gate adds the
 * per-series runtime cursor required to detect duplicates, out-of-order completed candles and
 * gaps before a strategy can observe them. A series is identified by the complete instrument
 * identity ({@code exchange + marketType + symbol}) and timeframe.
 */
@Slf4j
@Component
public class MarketDataQualityGate {

    private static final String ACTOR = "MARKET_DATA_QUALITY_GATE";
    private static final int RECENT_COMPLETED_BARS_PER_SERIES = 2_048;

    private final DataQualityChecker checker;
    private final KillSwitch killSwitch;
    private final ConcurrentHashMap<BarSeriesKey, SeriesState> seriesStates =
            new ConcurrentHashMap<>();

    @Autowired
    public MarketDataQualityGate(DataQualityChecker checker, KillSwitch killSwitch) {
        this.checker = Objects.requireNonNull(checker, "checker");
        this.killSwitch = Objects.requireNonNull(killSwitch, "killSwitch");
    }

    /**
     * Validate a candle and, for completed candles, atomically advance its series cursor.
     *
     * <p>Forming candles are structurally validated but do not affect ordering state. An exact
     * replay of the latest completed candle is idempotently dropped. Any other invalid sequence
     * transition is rejected and activates the global kill switch in HALT mode.
     */
    public Decision evaluate(BarEvent bar) {
        Objects.requireNonNull(bar, "bar");

        DataQualityReport anomalyReport = checker.checkAnomalies(List.of(bar));
        if (anomalyReport.hasIssues()) {
            return reject(bar, "INVALID_BAR", String.join("; ", anomalyReport.issues()));
        }
        if (!bar.closed()) {
            return Decision.ACCEPT;
        }

        BarSeriesKey key = BarSeriesKey.from(bar);
        ClosedBarSnapshot candidate = ClosedBarSnapshot.from(bar);
        AtomicReference<Decision> decision = new AtomicReference<>(Decision.ACCEPT);
        AtomicReference<String> rejection = new AtomicReference<>();

        seriesStates.compute(key, (ignored, state) -> {
            if (state == null) {
                return new SeriesState(candidate);
            }

            long currentTimestamp = candidate.exchangeTimestamp();
            ClosedBarSnapshot existing = state.at(currentTimestamp);
            if (existing != null) {
                if (candidate.samePayload(existing)) {
                    decision.set(Decision.DUPLICATE);
                } else {
                    decision.set(Decision.REJECT);
                    rejection.set("completed candle changed after close at timestamp "
                            + currentTimestamp);
                }
                return state;
            }

            long previousTimestamp = state.latest().exchangeTimestamp();
            if (currentTimestamp < previousTimestamp) {
                decision.set(Decision.REJECT);
                rejection.set("out-of-order completed candle: previous=" + previousTimestamp
                        + ", current=" + currentTimestamp);
                return state;
            }

            long expectedInterval = key.timeframe().getMillis();
            long actualInterval = currentTimestamp - previousTimestamp;
            if (actualInterval != expectedInterval) {
                decision.set(Decision.REJECT);
                if (actualInterval > expectedInterval) {
                    long missingBars = Math.max(1L, (actualInterval - 1L) / expectedInterval);
                    rejection.set("runtime candle gap: previous=" + previousTimestamp
                            + ", current=" + currentTimestamp + ", expectedInterval="
                            + expectedInterval + ", missingBars=" + missingBars);
                } else {
                    rejection.set("misaligned completed candle: previous=" + previousTimestamp
                            + ", current=" + currentTimestamp + ", expectedInterval="
                            + expectedInterval);
                }
                return state;
            }

            state.append(candidate);
            return state;
        });

        if (decision.get() == Decision.REJECT) {
            return reject(bar, "INVALID_SEQUENCE", rejection.get());
        }
        if (decision.get() == Decision.DUPLICATE) {
            log.debug("Idempotently dropped duplicate completed bar: series={}, timestamp={}",
                    key, candidate.exchangeTimestamp());
        }
        return decision.get();
    }

    int trackedSeriesCount() {
        return seriesStates.size();
    }

    private Decision reject(BarEvent bar, String category, String detail) {
        String series = safeSeries(bar);
        String reason = category + " series=" + series + ": " + detail;
        log.error("Rejecting market data before strategy dispatch: {}", reason);
        try {
            killSwitch.activate(KillSwitch.Mode.HALT, reason, ACTOR);
        } catch (RuntimeException persistenceFailure) {
            // The durable KillSwitch implementation forces its local mode to HALT before throwing.
            // This event must remain rejected even when persistence itself is unhealthy.
            log.error("Kill-switch persistence failed while rejecting bad market data", persistenceFailure);
        }
        return Decision.REJECT;
    }

    private String safeSeries(BarEvent bar) {
        if (bar.instrument() == null || bar.timeframe() == null) {
            return "UNKNOWN";
        }
        return bar.instrument().id().value() + ":" + bar.timeframe().getCode();
    }

    public enum Decision {
        ACCEPT(true),
        DUPLICATE(false),
        REJECT(false);

        private final boolean dispatchToStrategies;

        Decision(boolean dispatchToStrategies) {
            this.dispatchToStrategies = dispatchToStrategies;
        }

        public boolean dispatchToStrategies() {
            return dispatchToStrategies;
        }
    }

    private record BarSeriesKey(InstrumentId instrumentId, Timeframe timeframe) {
        private static BarSeriesKey from(BarEvent bar) {
            return new BarSeriesKey(bar.instrument().id(), bar.timeframe());
        }
    }

    /** Mutable only while held by ConcurrentHashMap.compute for its series key. */
    private static final class SeriesState {
        private final LinkedHashMap<Long, ClosedBarSnapshot> recentBars = new LinkedHashMap<>();
        private ClosedBarSnapshot latest;

        private SeriesState(ClosedBarSnapshot first) {
            append(first);
        }

        private ClosedBarSnapshot latest() {
            return latest;
        }

        private ClosedBarSnapshot at(long timestamp) {
            return recentBars.get(timestamp);
        }

        private void append(ClosedBarSnapshot bar) {
            recentBars.put(bar.exchangeTimestamp(), bar);
            latest = bar;
            if (recentBars.size() > RECENT_COMPLETED_BARS_PER_SERIES) {
                Long oldest = recentBars.keySet().iterator().next();
                recentBars.remove(oldest);
            }
        }
    }

    private record ClosedBarSnapshot(
            long exchangeTimestamp,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            BigDecimal quoteVolume
    ) {
        private static ClosedBarSnapshot from(BarEvent bar) {
            return new ClosedBarSnapshot(
                    bar.metadata().exchangeTimestamp(), bar.open(), bar.high(), bar.low(),
                    bar.close(), bar.volume(), bar.quoteVolume());
        }

        private boolean samePayload(ClosedBarSnapshot other) {
            return equalDecimal(open, other.open)
                    && equalDecimal(high, other.high)
                    && equalDecimal(low, other.low)
                    && equalDecimal(close, other.close)
                    && equalDecimal(volume, other.volume)
                    && equalDecimal(quoteVolume, other.quoteVolume);
        }

        private static boolean equalDecimal(BigDecimal left, BigDecimal right) {
            return left.compareTo(right) == 0;
        }
    }
}
