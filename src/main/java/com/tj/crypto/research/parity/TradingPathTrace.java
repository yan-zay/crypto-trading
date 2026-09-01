package com.tj.crypto.research.parity;

import java.math.BigDecimal;
import java.util.List;

/** Content references and normalized economic facts from one replay path. */
public record TradingPathTrace(Path path, String canonicalInputSha256,
                               String strategyArtifactSha256,
                               List<String> signalSha256,
                               List<String> orderFactSha256,
                               BigDecimal pnl, BigDecimal fees,
                               String terminalPortfolioSha256) {
    public TradingPathTrace {
        if (path == null || !hash(canonicalInputSha256) || !hash(strategyArtifactSha256)
                || signalSha256 == null || orderFactSha256 == null
                || signalSha256.stream().anyMatch(value -> !hash(value))
                || orderFactSha256.stream().anyMatch(value -> !hash(value))
                || pnl == null || fees == null || !hash(terminalPortfolioSha256)) {
            throw new IllegalArgumentException("complete trading path trace is required");
        }
        signalSha256 = List.copyOf(signalSha256);
        orderFactSha256 = List.copyOf(orderFactSha256);
        pnl = pnl.stripTrailingZeros();
        fees = fees.stripTrailingZeros();
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    public enum Path { BACKTEST, PAPER, SHADOW }
}
