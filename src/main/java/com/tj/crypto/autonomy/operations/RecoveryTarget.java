package com.tj.crypto.autonomy.operations;

/** Recovery is deliberately stepwise: HALTED/MANUAL -> DEGRADED, then DEGRADED -> NORMAL. */
public enum RecoveryTarget {
    DEGRADED,
    NORMAL
}
