package com.tj.crypto.portfolio.analytics;

/** Position-risk direction; independent from the BUY/SELL execution side. */
public enum ExposureDirection {
    LONG(1),
    SHORT(-1);

    private final int sign;

    ExposureDirection(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }
}
