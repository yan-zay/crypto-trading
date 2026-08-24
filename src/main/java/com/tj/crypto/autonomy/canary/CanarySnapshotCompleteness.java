package com.tj.crypto.autonomy.canary;

/** Derived from raw fields; callers cannot declare a snapshot complete. */
public enum CanarySnapshotCompleteness {
    COMPLETE,
    INCOMPLETE,
    ANOMALOUS
}
