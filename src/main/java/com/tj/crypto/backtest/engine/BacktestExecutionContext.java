package com.tj.crypto.backtest.engine;

import com.tj.crypto.research.experiment.ResearchExecutionLineage;

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
        return run(resultId, randomSeed, monitor, "system", action);
    }

    public static <T> T run(String resultId, long randomSeed,
                            BacktestProgressMonitor monitor, String operator, Supplier<T> action) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested backtest context is not supported");
        CURRENT.set(new State(resultId, randomSeed,
                monitor == null ? BacktestProgressMonitor.NONE : monitor,
                operator == null || operator.isBlank() ? "system" : operator.trim(), null));
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

    public static String operator() {
        State state = CURRENT.get();
        return state == null ? "system" : state.operator();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static void bindResearchLineage(ResearchExecutionLineage lineage) {
        State state = CURRENT.get();
        if (state == null) throw new IllegalStateException("backtest context is required");
        if (state.researchLineage() != null) {
            throw new IllegalStateException("research lineage is already bound");
        }
        CURRENT.set(new State(state.resultId(), state.randomSeed(), state.monitor(),
                state.operator(), java.util.Objects.requireNonNull(lineage, "lineage")));
    }

    public static ResearchExecutionLineage researchLineage() {
        State state = CURRENT.get();
        return state == null ? null : state.researchLineage();
    }

    private record State(String resultId, long randomSeed, BacktestProgressMonitor monitor,
                         String operator, ResearchExecutionLineage researchLineage) {}
}
