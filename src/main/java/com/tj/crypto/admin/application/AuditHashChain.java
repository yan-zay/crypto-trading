package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.AuditLogDO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical hash calculation shared by audit append and verification. */
final class AuditHashChain {
    static final String GENESIS_HASH = "0".repeat(64);

    private AuditHashChain() {
    }

    static String hash(AuditLogDO entry) {
        String canonical = fields(
                entry.getRequestId(), entry.getCorrelationId(), entry.getOperationType(),
                entry.getResourceType(), entry.getResourceId(), entry.getConfigType(),
                entry.getConfigKey(), entry.getVersionId(), entry.getOperator(), entry.getOutcome(),
                entry.getSourceIp(), number(entry.getLatencyMs()),
                number(entry.getOperationTime() == null ? null : entry.getOperationTime().getTime()),
                entry.getDetail());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(value(entry.getPreviousHash()).getBytes(StandardCharsets.UTF_8));
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String fields(String... fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            String value = value(field);
            canonical.append(value.length()).append(':').append(value);
        }
        return canonical.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String number(Number value) {
        return value == null ? "" : value.toString();
    }
}
