package com.tj.crypto.research.experiment;

import java.util.Locale;
import java.util.regex.Pattern;

final class ExperimentValidation {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ExperimentValidation() {
    }

    static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    static String sha256(String value, String field) {
        String normalized = required(value, field, 64).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return normalized;
    }
}
