package com.tj.crypto.autonomy.operations;

/** Health asserted by an authoritative, content-addressed fact producer. */
public enum OperationsHealth {
    HEALTHY,
    DEGRADED,
    FAILED,
    UNKNOWN
}
