package com.tj.crypto.autonomy.capital;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class CapitalValues {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private CapitalValues() {
    }

    static String id(String value, String field, int maxLength) {
        String normalized = value == null ? ""
                : Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return normalized;
    }

    static String code(String value, String field, int maxLength) {
        return id(value, field, maxLength).toUpperCase(Locale.ROOT);
    }

    static String sha256(String value, String field) {
        String normalized = id(value, field, 64).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    static BigDecimal decimal(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.stripTrailingZeros();
    }

    static BigDecimal nonNegative(BigDecimal value, String field) {
        BigDecimal normalized = decimal(value, field);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return normalized;
    }

    static BigDecimal positive(BigDecimal value, String field) {
        BigDecimal normalized = decimal(value, field);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return normalized;
    }

    static String canonicalId(String prefix, String... components) {
        StringBuilder result = new StringBuilder(prefix);
        for (String component : components) {
            result.append('|').append(component.length()).append(':').append(component);
        }
        return result.toString();
    }
}
