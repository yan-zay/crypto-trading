package com.tj.crypto.portfolio.runtime;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.portfolio.analytics.SnapshotCompleteness;
import com.tj.crypto.trading.instrument.InstrumentMetadata;
import com.tj.crypto.trading.instrument.InstrumentMetadataService;
import com.tj.crypto.trading.paper.PaperAccountSnapshot;
import com.tj.crypto.trading.paper.PaperTradingQueryService;
import com.tj.crypto.trading.paper.persistence.PaperAccountDO;
import com.tj.crypto.trading.paper.persistence.PaperMarkPriceDO;
import com.tj.crypto.trading.paper.persistence.PaperPositionDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperPortfolioSnapshotServiceTest {

    @Test
    void createsCompleteContentAddressedPaperFactsWithoutExecutionAuthority() {
        PaperTradingQueryService query = mock(PaperTradingQueryService.class);
        InstrumentMetadataService metadata = mock(InstrumentMetadataService.class);
        when(query.snapshot(null)).thenReturn(accountSnapshot());
        when(query.marks()).thenReturn(List.of(mark()));
        when(metadata.require(any(com.tj.crypto.common.domain.Instrument.class), anyLong()))
                .thenReturn(instrumentMetadata());
        PaperPortfolioSnapshotService service = new PaperPortfolioSnapshotService(query, metadata,
                Clock.fixed(Instant.ofEpochMilli(120_000), ZoneOffset.UTC));

        PaperPortfolioSnapshotReport report = service.snapshot(null, 120_000);

        assertThat(report.executionAuthorized()).isFalse();
        assertThat(report.exposureSnapshot().completeness()).isEqualTo(SnapshotCompleteness.COMPLETE);
        assertThat(report.exposureSnapshot().exposures()).hasSize(1);
        assertThat(report.equitySnapshot().equity()).isEqualByComparingTo("10100");
    }

    private PaperAccountSnapshot accountSnapshot() {
        PaperAccountDO account = new PaperAccountDO();
        account.setAccountId("paper-1");
        account.setStatus("RUNNING");
        account.setBaseCurrency("USDT");
        PaperPositionDO position = new PaperPositionDO();
        position.setAccountId("paper-1");
        position.setExchange("BINANCE");
        position.setMarketType("PERPETUAL");
        position.setSymbol("BTCUSDT");
        position.setSide("LONG");
        position.setQuantity(new BigDecimal("0.1"));
        position.setMarkPrice(new BigDecimal("50000"));
        position.setContractMultiplier(BigDecimal.ONE);
        position.setStrategyId("MacdCross");
        return new PaperAccountSnapshot(account, List.of(), List.of(position),
                BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.valueOf(100), BigDecimal.valueOf(10_100), 0, 0, null);
    }

    private PaperMarkPriceDO mark() {
        PaperMarkPriceDO mark = new PaperMarkPriceDO();
        mark.setExchange("BINANCE");
        mark.setMarketType("PERPETUAL");
        mark.setSymbol("BTCUSDT");
        mark.setPrice(new BigDecimal("50000"));
        mark.setEventTimeMs(60_000L);
        mark.setSource("BAR_EVENT");
        return mark;
    }

    private InstrumentMetadata instrumentMetadata() {
        return new InstrumentMetadata(1, Exchange.BINANCE, MarketType.PERPETUAL,
                "BTCUSDT", "BTCUSDT", "BTC", "USDT", "USDT", "TRADING",
                new BigDecimal("0.1"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("100"),
                new BigDecimal("5"), BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, 10, new BigDecimal("0.005"), 8,
                1, null, "fixture");
    }
}
