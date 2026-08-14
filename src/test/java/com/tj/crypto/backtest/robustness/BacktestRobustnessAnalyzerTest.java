package com.tj.crypto.backtest.robustness;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestRobustnessAnalyzerTest {
    private final BacktestRobustnessAnalyzer analyzer = new BacktestRobustnessAnalyzer();
    private final Instrument instrument = Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT");

    @Test
    void bootstrapIsDeterministicForSameSeed() {
        List<Trade> trades = IntStream.range(0, 60)
                .mapToObj(i -> trade(i % 4 == 0 ? -5 : 10, i)).toList();
        BacktestRobustnessReport first = analyzer.analyze(trades, 1234L);
        BacktestRobustnessReport second = analyzer.analyze(trades, 1234L);

        assertThat(first).isEqualTo(second);
        assertThat(first.bootstrapSamples()).isEqualTo(2000);
        assertThat(first.probabilityMeanPositive()).isGreaterThan(new BigDecimal("0.9"));
        assertThat(first.warnings())
                .anyMatch(text -> text.contains("30-90 day forward paper/shadow"));
    }

    @Test
    void marksTinySamplesAsInsufficient() {
        BacktestRobustnessReport report = analyzer.analyze(List.of(trade(1, 1)), 42L);
        assertThat(report.evidenceGrade()).isEqualTo("INSUFFICIENT");
        assertThat(report.warnings()).isNotEmpty();
    }

    private Trade trade(double pnl, long time) {
        return new Trade(instrument, OrderSide.LONG, BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("101"), time, time + 1,
                BigDecimal.valueOf(pnl), BigDecimal.ZERO);
    }
}
