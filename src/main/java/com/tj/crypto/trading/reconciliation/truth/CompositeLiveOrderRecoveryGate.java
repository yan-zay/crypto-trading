package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.trading.reconciliation.LiveOrderRecoveryGate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The live opening-risk gate is READY only when known-order recovery and account-wide venue
 * truth have both converged. Neither coordinator can overwrite the other coordinator's block.
 */
@Primary
@Component
public class CompositeLiveOrderRecoveryGate extends LiveOrderRecoveryGate {
    private final AtomicReference<State> internalState = new AtomicReference<>(State.PENDING);
    private final AtomicReference<State> truthState = new AtomicReference<>(State.PENDING);

    @Override
    public void markReady() {
        internalState.set(State.READY);
    }

    @Override
    public void markBlocked() {
        internalState.set(State.BLOCKED);
    }

    public void markTruthReady() {
        truthState.set(State.READY);
    }

    public void markTruthBlocked() {
        truthState.set(State.BLOCKED);
    }

    public void markTruthPending() {
        truthState.set(State.PENDING);
    }

    public State internalState() {
        return internalState.get();
    }

    public State truthState() {
        return truthState.get();
    }

    @Override
    public State state() {
        State internal = internalState.get();
        State truth = truthState.get();
        if (internal == State.BLOCKED || truth == State.BLOCKED) return State.BLOCKED;
        if (internal == State.READY && truth == State.READY) return State.READY;
        return State.PENDING;
    }

    @Override
    public boolean isReady() {
        return state() == State.READY;
    }

    @Override
    public void requireReadyForOpeningRisk() {
        State combined = state();
        if (combined != State.READY) {
            throw new IllegalStateException("Live opening risk requires internal and venue truth READY; "
                    + "state=" + combined + ", internal=" + internalState.get()
                    + ", venueTruth=" + truthState.get());
        }
    }
}
