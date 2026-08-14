package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate;

import java.util.List;

/** Immutable result of one account-wide venue/internal truth convergence attempt. */
public record VenueTruthRun(Trigger trigger, long checkedAtMs,
                            LiveOrderRecoveryGate.State state,
                            List<Exchange> configuredGateways,
                            List<VenueTruthDifference> differences) {
    public VenueTruthRun {
        if (trigger == null || state == null) throw new IllegalArgumentException("run state is required");
        configuredGateways = configuredGateways == null ? List.of() : List.copyOf(configuredGateways);
        differences = differences == null ? List.of() : List.copyOf(differences);
    }

    public boolean converged() {
        return state == LiveOrderRecoveryGate.State.READY && differences.isEmpty();
    }

    public enum Trigger { STARTUP, PERIODIC, MANUAL }
}
