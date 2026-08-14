package com.tj.crypto.strategy.core;

/** Downstream port invoked after a strategy signal has been accepted by the engine. */
public interface SignalListener {
    void onSignal(SignalEvent signal);
}
