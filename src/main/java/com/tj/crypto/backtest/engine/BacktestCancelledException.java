package com.tj.crypto.backtest.engine;

public class BacktestCancelledException extends RuntimeException {
    public BacktestCancelledException() {
        super("Backtest was cancelled");
    }
}
