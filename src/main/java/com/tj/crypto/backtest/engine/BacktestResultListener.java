package com.tj.crypto.backtest.engine;

/** Receives an immutable, completed backtest result. */
public interface BacktestResultListener {
    void onCompleted(BacktestResult result);

    /** Required listeners make the run fail if its result cannot be committed. */
    default boolean requiredForCompletion() {
        return false;
    }
}
