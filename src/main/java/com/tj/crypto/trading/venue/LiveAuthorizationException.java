package com.tj.crypto.trading.venue;

/** A deterministic pre-dispatch rejection: no venue mutation has been sent. */
public class LiveAuthorizationException extends IllegalStateException {
    public LiveAuthorizationException(String message) {
        super(message);
    }
}
