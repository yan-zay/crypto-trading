package com.tj.crypto.autonomy.operations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class OperationsHashing {
    private OperationsHashing() {
    }

    static String sha256(Object... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (Object value : values) {
                    if (value == null) {
                        output.writeInt(-1);
                    } else {
                        byte[] encoded = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                        output.writeInt(encoded.length);
                        output.write(encoded);
                    }
                }
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory canonicalization failed", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    static boolean safeText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.equals(value.trim())
                && value.length() <= maximumLength
                && value.chars().noneMatch(Character::isISOControl);
    }
}
