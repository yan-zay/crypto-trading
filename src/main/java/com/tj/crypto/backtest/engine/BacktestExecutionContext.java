package com.tj.crypto.backtest.engine;

import java.util.function.Supplier;

/** Thread-confined run identity and cooperative cancellation context. */
public final class BacktestExecutionContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private BacktestExecutionContext() {}

    public static <T> T run(String resultId, BacktestProgressMonitor monitor, Supplier<T> action) {
        return run(resultId, 42L, monitor, action);
    }

    public static <T> T run(String resultId, long randomSeed,
                            BacktestProgressMonitor monitor, Supplier<T> action) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested backtest context is not supported");
        CURRENT.set(new State(resultId, randomSeed,
                monitor == null ? BacktestProgressMonitor.NONE : monitor));
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    public static String resultId() {
        State state = CURRENT.get();
        return state == null ? null : state.resultId();
    }

    public static BacktestProgressMonitor monitor() {
        State state = CURRENT.get();
        return state == null ? BacktestProgressMonitor.NONE : state.monitor();
    }

    public static long randomSeed() {
        State state = CURRENT.get();
        return state == null ? 42L : state.randomSeed();
    }

    private record State(String resultId, long randomSeed, BacktestProgressMonitor monitor) {}
}
