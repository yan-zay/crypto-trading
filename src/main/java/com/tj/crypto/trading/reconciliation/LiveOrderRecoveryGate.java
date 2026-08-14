package com.tj.crypto.trading.reconciliation;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/** Opening-risk gate which is PENDING until the startup live-order truth scan succeeds. */
@Component
public class LiveOrderRecoveryGate {
    public enum State { PENDING, READY, BLOCKED }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    public void markReady() {
        state.set(State.READY);
    }

    public void markBlocked() {
        state.set(State.BLOCKED);
    }

    public State state() {
        return state.get();
    }

    public boolean isReady() {
        return state.get() == State.READY;
    }

    public void requireReadyForOpeningRisk() {
        State current = state.get();
        if (current != State.READY) {
            throw new IllegalStateException(
                    "Live opening risk is blocked until order recovery is READY; state=" + current);
        }
    }
}
