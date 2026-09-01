package com.tj.crypto.trading.pipeline;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.TradeSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicTargetPipelineTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void compilesOpeningAndReductionWithoutGrantingExecution() {
        DeterministicTargetPipeline pipeline = new DeterministicTargetPipeline();
        OrderIntent opening = pipeline.plan(adjusted("2", "2"), position("0"),
                new BigDecimal("100"), NOW);
        OrderIntent reduction = pipeline.plan(adjusted("1", "1"), position("2"),
                new BigDecimal("100"), NOW);

        assertThat(opening.side()).isEqualTo(TradeSide.BUY);
        assertThat(opening.reduceOnly()).isFalse();
        assertThat(opening.executionAuthorized()).isFalse();
        assertThat(reduction.side()).isEqualTo(TradeSide.SELL);
        assertThat(reduction.reduceOnly()).isTrue();
    }

    @Test
    void rejectsCrossZeroAndRiskLimitExpansion() {
        DeterministicTargetPipeline pipeline = new DeterministicTargetPipeline();
        assertThatThrownBy(() -> pipeline.plan(adjusted("-1", "3"), position("2"),
                BigDecimal.TEN, NOW)).hasMessageContaining("separate flat target");
        assertThatThrownBy(() -> pipeline.plan(adjusted("3", "1"), position("0"),
                BigDecimal.TEN, NOW)).hasMessageContaining("risk-adjusted order limit");
    }

    private RiskAdjustedTarget adjusted(String target, String max) {
        return new RiskAdjustedTarget(new PortfolioTarget("strategy", instrument().id(),
                new BigDecimal(target), "BASE", "risk-v1", HASH,
                NOW.minusSeconds(1), NOW.plusSeconds(60)), new BigDecimal(max), HASH);
    }

    private PositionSnapshot position(String quantity) {
        return new PositionSnapshot(instrument().id(), new BigDecimal(quantity), "BASE", HASH,
                NOW.minusSeconds(1), NOW.plusSeconds(60));
    }

    private Instrument instrument() {
        return Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");
    }
}
