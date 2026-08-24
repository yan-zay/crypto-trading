package com.tj.crypto.trading.venue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned, length-prefixed canonical hashes used by approvals and final venue permits. */
final class LiveOrderCommandHasher {
    private LiveOrderCommandHasher() {}

    static String hash(VenueOrderCommand command) {
        return digest("LIVE_ORDER_V1", command.accountId(), command.clientOrderId(),
                command.strategyId(), command.instrument().exchange().name(),
                command.instrument().marketType().name(), command.instrument().symbol(),
                command.tradeSide().name(), value(command.positionSide()), command.orderType().name(),
                decimal(command.quantity()), decimal(command.price()),
                Boolean.toString(command.reduceOnly()), Integer.toString(command.leverage()),
                command.marginMode());
    }

    static String hash(VenueCancelCommand command) {
        return digest("LIVE_CANCEL_V1", command.instrument().exchange().name(),
                command.instrument().marketType().name(), command.instrument().symbol(),
                command.venueOrderId(), command.clientOrderId());
    }

    static String evidenceDigest(java.util.Map<?, String> evidence) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        fields.add("LIVE_EVIDENCE_SET_V1");
        evidence.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> {
                    fields.add(entry.getKey().toString());
                    fields.add(entry.getValue());
                });
        return digest(fields.toArray(String[]::new));
    }

    static String evidenceReferences(java.util.Map<?, String> evidence) {
        return evidence.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String digest(String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String field : fields) {
                    byte[] encoded = (field == null ? "<NULL>" : field)
                            .getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to canonicalize live command", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String value(Object value) {
        return value == null ? null : value.toString();
    }
}
