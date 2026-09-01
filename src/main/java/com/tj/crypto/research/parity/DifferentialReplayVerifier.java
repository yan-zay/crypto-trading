package com.tj.crypto.research.parity;

import java.math.BigDecimal;
import java.util.ArrayList;

/** Compares complete decision/order trajectories, not only final PnL. */
public final class DifferentialReplayVerifier {
    public DifferentialReplayReport compare(TradingPathTrace expected, TradingPathTrace actual,
                                            BigDecimal pnlTolerance, BigDecimal feeTolerance) {
        if (expected == null || actual == null || pnlTolerance == null || feeTolerance == null
                || pnlTolerance.signum() < 0 || feeTolerance.signum() < 0) {
            throw new IllegalArgumentException("valid traces and non-negative tolerances are required");
        }
        ArrayList<String> differences = new ArrayList<>();
        if (!expected.canonicalInputSha256().equals(actual.canonicalInputSha256())) {
            differences.add("INPUT_ROOT_MISMATCH");
        }
        if (!expected.strategyArtifactSha256().equals(actual.strategyArtifactSha256())) {
            differences.add("STRATEGY_ARTIFACT_MISMATCH");
        }
        if (!expected.signalSha256().equals(actual.signalSha256())) differences.add("SIGNAL_TRACE_MISMATCH");
        if (!expected.orderFactSha256().equals(actual.orderFactSha256())) differences.add("ORDER_TRACE_MISMATCH");
        if (expected.pnl().subtract(actual.pnl()).abs().compareTo(pnlTolerance) > 0) {
            differences.add("PNL_OUTSIDE_TOLERANCE");
        }
        if (expected.fees().subtract(actual.fees()).abs().compareTo(feeTolerance) > 0) {
            differences.add("FEES_OUTSIDE_TOLERANCE");
        }
        if (!expected.terminalPortfolioSha256().equals(actual.terminalPortfolioSha256())) {
            differences.add("TERMINAL_PORTFOLIO_MISMATCH");
        }
        return new DifferentialReplayReport(differences.isEmpty(), differences);
    }
}
