package com.tj.crypto.backtest.engine;

public interface BacktestProgressMonitor {
    BacktestProgressMonitor NONE = new BacktestProgressMonitor() {};

    default boolean isCancellationRequested() {
        return false;
    }

    default void onProgress(int processed, int total) {}

    default void checkpoint() {
        if (isCancellationRequested()) throw new BacktestCancelledException();
    }
}
