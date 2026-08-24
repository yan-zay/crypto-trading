package com.tj.crypto.autonomy.readiness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class ReadinessHashing {
    private ReadinessHashing() {
    }

    static String sha256(String domain, List<String> components) {
        String canonical = components.stream()
                .map(value -> value.length() + ":" + value)
                .reduce(domain, (left, right) -> left + "|" + right);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
