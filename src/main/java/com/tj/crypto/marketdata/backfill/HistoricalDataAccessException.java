package com.tj.crypto.marketdata.backfill;

/** Signals that an upstream historical market-data request or payload failed. */
public class HistoricalDataAccessException extends RuntimeException {

    public HistoricalDataAccessException(String message) {
        super(message);
    }

    public HistoricalDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
