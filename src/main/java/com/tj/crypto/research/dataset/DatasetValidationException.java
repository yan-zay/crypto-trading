package com.tj.crypto.research.dataset;

/** Fail-closed error raised when an immutable research dataset violates its contract. */
public final class DatasetValidationException extends IllegalArgumentException {
    private final String code;
    private final long rowNumber;

    public DatasetValidationException(String code, String message) {
        this(code, -1, message);
    }

    public DatasetValidationException(String code, long rowNumber, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        this.code = code;
        this.rowNumber = rowNumber;
    }

    public String code() {
        return code;
    }

    /** One-based CSV data-row number, or {@code -1} when the error is not row-specific. */
    public long rowNumber() {
        return rowNumber;
    }
}
