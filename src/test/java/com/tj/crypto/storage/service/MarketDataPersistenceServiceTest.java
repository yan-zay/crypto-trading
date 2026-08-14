package com.tj.crypto.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.storage.mapper.BarEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MarketDataPersistenceServiceTest {
    @TempDir
    Path temp;

    @Test
    void durablySpoolsBeforeDatabaseFlushAndAcknowledgesAfterSuccess() throws Exception {
        BarEventMapper mapper = mock(BarEventMapper.class);
        MarketDataPersistenceService service = service(mapper, new KillSwitch());

        service.persistBarAsync(bar());

        assertThat(service.pendingCount()).isEqualTo(1);
        assertThat(Files.readString(service.spoolPath())).isNotBlank();
        service.flush();
        verify(mapper).upsertBatch(anyCollection());
        assertThat(service.pendingCount()).isZero();
        assertThat(Files.readString(service.spoolPath())).isEmpty();
    }

    @Test
    void databaseFailureKeepsSpoolAndHaltsTrading() throws Exception {
        BarEventMapper mapper = mock(BarEventMapper.class);
        doThrow(new IllegalStateException("db unavailable")).when(mapper).upsertBatch(anyCollection());
        KillSwitch killSwitch = new KillSwitch();
        MarketDataPersistenceService service = service(mapper, killSwitch);
        service.persistBarAsync(bar());

        assertThatThrownBy(service::flush).hasMessageContaining("db unavailable");

        assertThat(killSwitch.getMode()).isEqualTo(KillSwitch.Mode.HALT);
        assertThat(service.pendingCount()).isEqualTo(1);
        assertThat(Files.readString(service.spoolPath())).isNotBlank();
    }

    @Test
    void startupReplaysUnacknowledgedSpoolIdempotently() throws Exception {
        BarEventMapper firstMapper = mock(BarEventMapper.class);
        MarketDataPersistenceService first = service(firstMapper, new KillSwitch());
        first.persistBarAsync(bar());

        BarEventMapper recoveryMapper = mock(BarEventMapper.class);
        MarketDataPersistenceService recovered = service(recoveryMapper, new KillSwitch());
        recovered.recoverSpool();

        verify(recoveryMapper).upsertBatch(anyCollection());
        assertThat(recovered.pendingCount()).isZero();
        assertThat(Files.readString(recovered.spoolPath())).isEmpty();
    }

    private MarketDataPersistenceService service(BarEventMapper mapper, KillSwitch killSwitch) {
        return new MarketDataPersistenceService(mapper, new SyncTaskExecutor(), new ObjectMapper(),
                killSwitch, temp.resolve("bars.jsonl").toString(), 100);
    }

    private BarEvent bar() {
        long open = 1_700_000_000_000L;
        return new BarEvent(Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"),
                EventMetadata.of(Exchange.BINANCE, open), Timeframe.M1,
                new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("99"),
                new BigDecimal("101"), BigDecimal.TEN, new BigDecimal("1010"), true);
    }
}
