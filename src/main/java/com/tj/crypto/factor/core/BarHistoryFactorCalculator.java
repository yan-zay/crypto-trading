package com.tj.crypto.factor.core;

/**
 * Marker contract for factors that can be deterministically recomputed from a finalized
 * candle slice. Event-state factors must not implement this interface until their event
 * history is part of the backtest replay stream.
 */
public interface BarHistoryFactorCalculator extends FactorCalculator {
}
