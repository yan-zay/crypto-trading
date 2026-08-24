package com.tj.crypto.research.experiment;

/** State derived from all immutable trial histories; it is never updated in place. */
public enum ExperimentFamilyState {
    REGISTERED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
