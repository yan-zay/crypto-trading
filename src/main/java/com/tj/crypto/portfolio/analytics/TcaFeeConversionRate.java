package com.tj.crypto.portfolio.analytics;

import java.math.BigDecimal;
import java.util.Locale;

/** Point-in-time fee-currency conversion fact with availability time and stable source id. */
public record TcaFeeConversionRate(
        String feeCurrency,
        String quoteCurrency,
        long eventTimeMs,
        long observedAtMs,
        BigDecimal quotePerFeeCurrency,
        String sourceEventId
) {
    public TcaFeeConversionRate {
        feeCurrency = required(feeCurrency, "feeCurrency").toUpperCase(Locale.ROOT);
        quoteCurrency = required(quoteCurrency, "quoteCurrency").toUpperCase(Locale.ROOT);
        if (feeCurrency.equals(quoteCurrency)) {
            throw new IllegalArgumentException("fee conversion currencies must differ");
        }
        if (eventTimeMs <= 0 || observedAtMs < eventTimeMs) {
            throw new IllegalArgumentException("invalid fee conversion event/observation time");
        }
        if (quotePerFeeCurrency == null || quotePerFeeCurrency.signum() <= 0) {
            throw new IllegalArgumentException("quotePerFeeCurrency must be positive");
        }
        quotePerFeeCurrency = quotePerFeeCurrency.stripTrailingZeros();
        sourceEventId = required(sourceEventId, "sourceEventId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
