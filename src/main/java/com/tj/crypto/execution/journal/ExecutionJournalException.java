package com.tj.crypto.execution.journal;

/** Raised when a required OMS journal cannot durably record an order transition. */
public class ExecutionJournalException extends IllegalStateException {
    public ExecutionJournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
