package com.tj.crypto.research.experiment;

/** Same idempotency key with different immutable content, or duplicate content under a new key. */
public class ExperimentRegistrationConflictException extends IllegalStateException {
    public ExperimentRegistrationConflictException(String message) {
        super(message);
    }
}
