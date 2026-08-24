package com.tj.crypto.autonomy.operations;

/** Deterministic operating posture. Restrictive states never relax on a health observation alone. */
public enum OperationsState {
    NORMAL,
    DEGRADED,
    HALTED,
    MANUAL_TAKEOVER,
    RECOVERY_PENDING
}
