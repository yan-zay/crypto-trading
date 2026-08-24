package com.tj.crypto.autonomy.readiness;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class ReadinessValues {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ReadinessValues() {
    }

    static String required(String value, String name, int maxLength) {
        String normalized = value == null ? ""
                : Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return normalized;
    }

    static String hash(String value, String name) {
        String normalized = required(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    static BigDecimal positive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value.stripTrailingZeros();
    }
}
