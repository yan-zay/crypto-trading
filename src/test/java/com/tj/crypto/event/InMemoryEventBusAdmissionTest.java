package com.tj.crypto.event;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.quality.DataQualityChecker;
import com.tj.crypto.marketdata.quality.MarketDataQualityGate;
import com.tj.crypto.risk.KillSwitch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventBusAdmissionTest {

    @Test
    void invalidBarIsRejectedBeforeConcreteOrParentSubscribersObserveIt() {
        KillSwitch killSwitch = new KillSwitch();
        InMemoryEventBus bus = new InMemoryEventBus(
                new MarketDataQualityGate(new DataQualityChecker(), killSwitch));
        List<BarEvent> concrete = new ArrayList<>();
        List<MarketEvent> parent = new ArrayList<>();
        bus.subscribe(BarEvent.class, concrete::add);
        bus.subscribe(MarketEvent.class, parent::add);

        bus.publish(bar(1_000));
        bus.publish(bar(121_000));

        assertThat(concrete).hasSize(1);
        assertThat(parent).hasSize(1);
        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
    }

    @Test
    void exactClosedBarReplayIsDroppedBeforeAllSubscribers() {
        InMemoryEventBus bus = new InMemoryEventBus(
                new MarketDataQualityGate(new DataQualityChecker(), new KillSwitch()));
        List<BarEvent> observed = new ArrayList<>();
        bus.subscribe(BarEvent.class, observed::add);
        BarEvent value = bar(1_000);

        bus.publish(value);
        bus.publish(value);

        assertThat(observed).containsExactly(value);
    }

    private BarEvent bar(long timestamp) {
        Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
        return new BarEvent(instrument, EventMetadata.of(Exchange.BINANCE, timestamp), Timeframe.M1,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), BigDecimal.TEN, new BigDecimal("1050"), true);
    }
}
