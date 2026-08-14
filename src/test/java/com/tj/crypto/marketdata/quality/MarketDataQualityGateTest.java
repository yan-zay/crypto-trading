package com.tj.crypto.marketdata.quality;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.risk.KillSwitch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataQualityGateTest {

    private static final Instrument BINANCE_PERP =
            Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    private static final Instrument BINANCE_SPOT =
            Instrument.of(Exchange.BINANCE, MarketType.SPOT, "BTCUSDT");
    private static final Instrument OKX_PERP =
            Instrument.of(Exchange.OKX, MarketType.PERPETUAL, "BTCUSDT");

    private KillSwitch killSwitch;
    private MarketDataQualityGate gate;

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch();
        gate = new MarketDataQualityGate(new DataQualityChecker(), killSwitch);
    }

    @Test
    void acceptsStructurallyValidFormingAndContinuousCompletedBars() {
        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 1_000L, false)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);
        assertThat(gate.trackedSeriesCount()).isZero();

        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 1_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);
        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 61_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);

        assertThat(gate.trackedSeriesCount()).isEqualTo(1);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
    }

    @Test
    void isolatesOrderingStateByExchangeMarketSymbolAndTimeframe() {
        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 1_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);
        assertThat(gate.evaluate(bar(BINANCE_SPOT, Timeframe.M1, 1_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);
        assertThat(gate.evaluate(bar(OKX_PERP, Timeframe.M1, 1_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);
        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M5, 1_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);

        assertThat(gate.trackedSeriesCount()).isEqualTo(4);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
    }

    @Test
    void idempotentlyDropsIdenticalCompletedBarWithoutHalting() {
        BarEvent first = bar(BINANCE_PERP, Timeframe.M1, 1_000L, true);
        BarEvent replay = new BarEvent(first.instrument(),
                new EventMetadata(Exchange.BINANCE, 1_000L, 99_999L, "replay"),
                first.timeframe(), first.open(), first.high(), first.low(), first.close(),
                first.volume(), first.quoteVolume(), true);

        assertThat(gate.evaluate(first)).isEqualTo(MarketDataQualityGate.Decision.ACCEPT);
        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 61_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);
        assertThat(gate.evaluate(replay)).isEqualTo(MarketDataQualityGate.Decision.DUPLICATE);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.NORMAL);
    }

    @Test
    void rejectsConflictingCompletedReplayAndHalts() {
        BarEvent first = bar(BINANCE_PERP, Timeframe.M1, 1_000L, true);
        BarEvent changed = new BarEvent(first.instrument(), first.metadata(), first.timeframe(),
                first.open(), new BigDecimal("111"), first.low(), first.close(),
                first.volume(), first.quoteVolume(), true);

        assertThat(gate.evaluate(first)).isEqualTo(MarketDataQualityGate.Decision.ACCEPT);
        assertThat(gate.evaluate(changed)).isEqualTo(MarketDataQualityGate.Decision.REJECT);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void rejectsRuntimeGapAndHalts() {
        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 1_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);

        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 121_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.REJECT);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void rejectsOutOfOrderCompletedBarAndHalts() {
        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 61_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);

        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 1_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.REJECT);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void rejectsMisalignedCompletedBarAndHalts() {
        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 1_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);

        assertThat(gate.evaluate(bar(BINANCE_PERP, Timeframe.M1, 31_000L, true)))
                .isEqualTo(MarketDataQualityGate.Decision.REJECT);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void rejectsInvalidOhlcAndHaltsBeforeTrackingSeries() {
        BarEvent invalid = new BarEvent(BINANCE_PERP,
                EventMetadata.of(Exchange.BINANCE, 1_000L), Timeframe.M1,
                new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("90"),
                new BigDecimal("105"), BigDecimal.TEN, new BigDecimal("1050"), true);

        assertThat(gate.evaluate(invalid)).isEqualTo(MarketDataQualityGate.Decision.REJECT);
        assertThat(gate.trackedSeriesCount()).isZero();
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void acceptsSourceSpecificVolumeAndRejectsNoVolume() {
        BarEvent quoteVolumeOnly = new BarEvent(OKX_PERP,
                EventMetadata.of(Exchange.OKX, 1_000L), Timeframe.M1,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), BigDecimal.ZERO, new BigDecimal("1050"), true);
        assertThat(gate.evaluate(quoteVolumeOnly))
                .isEqualTo(MarketDataQualityGate.Decision.ACCEPT);

        BarEvent noVolume = new BarEvent(BINANCE_PERP,
                EventMetadata.of(Exchange.BINANCE, 1_000L), Timeframe.M1,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), BigDecimal.ZERO, BigDecimal.ZERO, true);
        assertThat(gate.evaluate(noVolume)).isEqualTo(MarketDataQualityGate.Decision.REJECT);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void rejectsMetadataSourceMismatch() {
        BarEvent invalid = new BarEvent(BINANCE_PERP,
                EventMetadata.of(Exchange.OKX, 1_000L), Timeframe.M1,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), BigDecimal.TEN, new BigDecimal("1050"), true);

        assertThat(gate.evaluate(invalid)).isEqualTo(MarketDataQualityGate.Decision.REJECT);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    private BarEvent bar(Instrument instrument, Timeframe timeframe, long timestamp, boolean closed) {
        return new BarEvent(instrument, EventMetadata.of(instrument.exchange(), timestamp), timeframe,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), BigDecimal.TEN, new BigDecimal("1050"), closed);
    }
}
