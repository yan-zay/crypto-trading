package com.tj.crypto.research.parity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DifferentialReplayVerifierTest {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);

    @Test
    void verifiesFullTraceAndExplicitEconomicTolerance() {
        DifferentialReplayVerifier verifier = new DifferentialReplayVerifier();
        TradingPathTrace backtest = trace(TradingPathTrace.Path.BACKTEST, A, "10.00");
        TradingPathTrace paper = trace(TradingPathTrace.Path.PAPER, A, "10.01");

        assertThat(verifier.compare(backtest, paper, new BigDecimal("0.02"), BigDecimal.ZERO)
                .withinTolerance()).isTrue();
        assertThat(verifier.compare(backtest, trace(TradingPathTrace.Path.PAPER, B, "10.01"),
                new BigDecimal("0.02"), BigDecimal.ZERO).differences())
                .contains("ORDER_TRACE_MISMATCH");
    }

    private TradingPathTrace trace(TradingPathTrace.Path path, String order, String pnl) {
        return new TradingPathTrace(path, A, B, List.of(C), List.of(order),
                new BigDecimal(pnl), BigDecimal.ONE, C);
    }
}
