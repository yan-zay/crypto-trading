package com.tj.crypto.trading.venue.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helper for content-addressed raw fixtures and redacted reports. */
public final class VenueContractEvidenceDigest {
    private VenueContractEvidenceDigest() {}

    public static String sha256(byte[] value) {
        if (value == null) throw new IllegalArgumentException("value is required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static String sha256(String value) {
        if (value == null) throw new IllegalArgumentException("value is required");
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
}
