package com.tj.crypto.trading.venue;

import java.util.Set;

/** A redacted venue failure; credentials and signed payloads must never enter the message. */
public class VenueApiException extends RuntimeException {
    /**
     * Codes whose documented semantics prove that a new order was not accepted. Keep this list
     * deliberately small; an unrecognised response is an indeterminate mutation, never a rejection.
     */
    private static final Set<String> BINANCE_HTTP_400_ORDER_REJECTIONS = Set.of(
            "-1013", "-1111", "-2010", "-2021", "-2022", "-4164");
    private static final Set<String> OKX_HTTP_200_ORDER_REJECTIONS = Set.of(
            "51000", "51006", "51008", "51020");

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

    public boolean definitivelyRejectsOrder() {
        if (httpStatus == 0 || httpStatus >= 500 || httpStatus == 408 || httpStatus == 429) {
            return false;
        }
        String normalizedCode = venueCode == null ? "" : venueCode.trim();
        return (httpStatus == 400 && BINANCE_HTTP_400_ORDER_REJECTIONS.contains(normalizedCode))
                || (httpStatus == 200 && OKX_HTTP_200_ORDER_REJECTIONS.contains(normalizedCode));
    }
}
