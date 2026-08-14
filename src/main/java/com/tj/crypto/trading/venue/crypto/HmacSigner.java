package com.tj.crypto.trading.venue.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

public final class HmacSigner {
    private HmacSigner() {}

    public static String sha256Hex(String secret, String payload) {
        return HexFormat.of().formatHex(sign(secret, payload));
    }

    public static String sha256Base64(String secret, String payload) {
        return Base64.getEncoder().encodeToString(sign(secret, payload));
    }

    private static byte[] sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
