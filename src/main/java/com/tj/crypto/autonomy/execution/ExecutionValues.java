package com.tj.crypto.autonomy.execution;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

final class ExecutionValues {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_DECIMAL_PRECISION = 50;
    private static final int MAX_DECIMAL_SCALE = 24;
    private static final int MAX_DECIMAL_TEXT = 96;

    private ExecutionValues() {
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
        BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        if (normalized.precision() > MAX_DECIMAL_PRECISION
                || Math.abs((long) normalized.scale()) > MAX_DECIMAL_SCALE
                || normalized.toPlainString().length() > MAX_DECIMAL_TEXT) {
            throw new IllegalArgumentException(field + " exceeds the exact decimal domain");
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

    static String digest(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static void append(StringBuilder target, Object component) {
        String value = component == null ? "<null>" : component.toString();
        target.append('|').append(value.length()).append(':').append(value);
    }
}
