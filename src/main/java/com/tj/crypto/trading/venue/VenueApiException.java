package com.tj.crypto.trading.venue;

/** A redacted venue failure; credentials and signed payloads must never enter the message. */
public class VenueApiException extends RuntimeException {
    private final String venueCode;
    private final int httpStatus;

    public VenueApiException(String message, String venueCode, int httpStatus) {
        super(message);
        this.venueCode = venueCode;
        this.httpStatus = httpStatus;
    }

    public String venueCode() {
        return venueCode;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
