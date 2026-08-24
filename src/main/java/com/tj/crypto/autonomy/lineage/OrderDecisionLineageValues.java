package com.tj.crypto.autonomy.lineage;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.text.Normalizer;
import java.util.regex.Pattern;

final class OrderDecisionLineageValues {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GIT_OBJECT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private OrderDecisionLineageValues() {
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.length() > 512 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    static String upper(String value, String name) {
        return text(value, name).toUpperCase(Locale.ROOT);
    }

    static String optionalText(String value, String name) {
        return value == null ? null : text(value, name);
    }

    static String digest(String value, String name) {
        String normalized = text(value, name);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return normalized;
    }

    static String optionalDigest(String value, String name) {
        return value == null ? null : digest(value, name);
    }

    static String gitObject(String value) {
        String normalized = text(value, "gitCommitHash");
        if (!GIT_OBJECT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("gitCommitHash must be a lowercase full Git object id");
        }
        return normalized;
    }

    static BigDecimal decimal(BigDecimal value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
    }

    static Instant millisecondInstant(Instant value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        if (!value.isAfter(Instant.EPOCH)) {
            throw new IllegalArgumentException(name + " must be after the Unix epoch");
        }
        if (!value.equals(Instant.ofEpochMilli(value.toEpochMilli()))) {
            throw new IllegalArgumentException(name + " must have millisecond precision");
        }
        return value;
    }

    static String sha256(CanonicalWriter writer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            writer.write(new DigestSink(digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
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

        DigestSink nullable(String value) {
            if (value == null) return value("0");
            return value("1").value(value);
        }

        DigestSink value(long value) {
            return value(Long.toString(value));
        }

        DigestSink value(Instant value) {
            return value(value.toString());
        }

        DigestSink value(BigDecimal value) {
            return value(decimal(value, "canonicalDecimal").toPlainString());
        }
    }
}
