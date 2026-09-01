package com.tj.crypto.marketdata.archive;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.event.InMemoryEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeMarketIngestCoordinatorTest {

    @TempDir
    Path temp;

    @Test
    void rawIsDurableBeforeBarOrLiquidationCanReachSubscribers() {
        LocalFsyncMarketIngestJournal journal = new LocalFsyncMarketIngestJournal(temp,
                Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC),
                new RecordingDirectoryDurabilityBarrier(), RealtimeCaptureCapability.WIRED);
        InMemoryEventBus bus = new InMemoryEventBus();
        RealtimeMarketIngestCoordinator coordinator = new RealtimeMarketIngestCoordinator(journal, bus);
        List<Object> observed = new ArrayList<>();
        bus.subscribe(BarEvent.class, observed::add);
        bus.subscribe(LiquidationEvent.class, observed::add);
        RealtimeMarketIngestCoordinator.RawCapture raw = coordinator.retainRaw(
                Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT", "kline",
                "application/json", "{\"event\":\"fixture\"}", 1_000, 1_001);

        BarEvent bar = coordinator.retainCanonicalAndPublish(raw, 0, bar()).event();
        LiquidationEvent liquidation = coordinator.retainCanonicalAndPublish(raw, 1, liquidation()).event();

        JournalReplay replay = journal.replay();
        assertThat(raw.rawMessageId()).hasSize(64);
        assertThat(bar.metadata().rawMessageId()).isEqualTo(raw.rawMessageId());
        assertThat(liquidation.metadata().rawMessageId()).isEqualTo(raw.rawMessageId());
        assertThat(replay.rawMessages()).hasSize(1);
        assertThat(replay.canonicalEvents()).hasSize(2);
        assertThat(observed).containsExactly(bar, liquidation);
    }

    private BarEvent bar() {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        return new BarEvent(instrument, new EventMetadata(Exchange.BINANCE, 1_000, 1_001, null),
                Timeframe.M1, decimal("100"), decimal("110"), decimal("90"), decimal("105"),
                decimal("2"), decimal("210"), true);
    }

    private LiquidationEvent liquidation() {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        return new LiquidationEvent(instrument,
                new EventMetadata(Exchange.BINANCE, 1_000, 1_001, null), OrderSide.LONG,
                decimal("100"), decimal("2"), decimal("200"), "Binance");
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
