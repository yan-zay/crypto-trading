package com.tj.crypto.autonomy.execution;

/** Venue-neutral time-in-force intent. A venue verifier must explicitly accept it. */
public enum ExecutionTimeInForce {
    DAY,
    GOOD_TIL_CANCELLED,
    IMMEDIATE_OR_CANCEL,
    FILL_OR_KILL
}
