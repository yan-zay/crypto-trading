package com.tj.crypto.research.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.backtest.engine.BacktestConfig;
import com.tj.crypto.backtest.engine.BacktestResult;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisteredBacktestExperimentServiceTest {

    @Test
    void freezesCompleteArtifactsBeforeStartingTrialAndBindsObservedProvenance() {
        ExperimentRegistryService registry = mock(ExperimentRegistryService.class);
        when(registry.register(any(), eq("alice"))).thenReturn(
                new ExperimentRegistrationReceipt("exp-1", "a".repeat(64), 1_000, false));
        when(registry.startTrial(eq("exp-1"), eq("trial-0"), any(),
                eq("backtest:run-1:start"), eq("alice"))).thenReturn(started());
        RegisteredBacktestExperimentService service = new RegisteredBacktestExperimentService(
                registry, new ObjectMapper().findAndRegisterModules(), "commit-1", "image-1");
        BacktestConfig config = new BacktestConfig(instrument(), Timeframe.M1,
                1_000, 1_000, 61_000, BigDecimal.valueOf(10_000));

        RegisteredBacktestExperimentService.PreparedTrial prepared = service.prepare(
                "run-1", "MacdCross", config, java.util.Map.of("strategy", "MacdCross"),
                bars(), 42, "alice");

        ArgumentCaptor<ExperimentFamilyRegistration> registration =
                ArgumentCaptor.forClass(ExperimentFamilyRegistration.class);
        verify(registry).register(registration.capture(), eq("alice"));
        assertThat(registration.getValue().artifacts()).hasSize(7);
        assertThat(registration.getValue().trials()).hasSize(1);
        assertThat(prepared.lineage()).isEqualTo(new ResearchExecutionLineage(
                "exp-1", "a".repeat(64), "trial-0", "b".repeat(64)));
    }

    private ExperimentEventAudit started() {
        return new ExperimentEventAudit("evt-1", "trial-0", "start", "c".repeat(64),
                2, "TRIAL_STARTED", ExperimentState.REGISTERED, ExperimentState.RUNNING,
                null, null, "b".repeat(64), null, "alice", 1_001,
                "d".repeat(64), "e".repeat(64));
    }

    private List<BarEvent> bars() {
        return List.of(bar(1_000, "100"), bar(61_000, "101"));
    }

    private BarEvent bar(long timestamp, String close) {
        BigDecimal price = new BigDecimal(close);
        return new BarEvent(instrument(), EventMetadata.of(Exchange.BINANCE, timestamp),
                Timeframe.M1, price, price, price, price, BigDecimal.ONE,
                price, true);
    }

    private Instrument instrument() {
        return Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }
}
