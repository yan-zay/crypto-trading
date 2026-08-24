package com.tj.crypto.autonomy.agent;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class AutonomyValues {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private AutonomyValues() {
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 512) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    static String digest(String value, String name) {
        String normalized = text(value, name).toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return normalized;
    }

    static BigDecimal positiveDecimal(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return canonicalDecimal(value);
    }

    static BigDecimal nonNegativeDecimal(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return canonicalDecimal(value);
    }

    static BigDecimal canonicalDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
    }

    static String sha256(CanonicalWriter writer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            writer.write(new DigestSink(digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    interface CanonicalWriter {
        void write(DigestSink sink);
    }

    static final class DigestSink {
        private final MessageDigest digest;

        private DigestSink(MessageDigest digest) {
            this.digest = digest;
        }

        DigestSink value(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
            return this;
        }

        DigestSink value(Instant value) {
            return value(value.toString());
        }

        DigestSink value(BigDecimal value) {
            return value(canonicalDecimal(value).toPlainString());
        }
    }
}
