package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.MarketType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 原始公共行情消息。它不是 canonical MarketEvent：一条聚合消息可以覆盖 ALL 或生成多个标的事件。
 */
public record RawMarketMessage(
        EventMetadata metadata,
        MarketType marketType,
        String symbolScope,
        String channel,
        String contentType,
        String payload,
        String payloadSha256
) implements ArchivableMarketData {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public RawMarketMessage {
        MarketEventValidation.requireTime(metadata);
        if (marketType == null) throw new IllegalArgumentException("marketType is required");
        symbolScope = required(symbolScope, "symbolScope").toUpperCase(Locale.ROOT);
        channel = required(channel, "channel");
        contentType = required(contentType, "contentType").toLowerCase(Locale.ROOT);
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("payload must not be blank");
        payloadSha256 = required(payloadSha256, "payloadSha256").toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(payloadSha256).matches()) {
            throw new IllegalArgumentException("payloadSha256 must be lowercase SHA-256");
        }
        if (!payloadSha256.equals(sha256(payload))) {
            throw new IllegalArgumentException("payloadSha256 does not match exact UTF-8 payload bytes");
        }
    }

    public static RawMarketMessage of(EventMetadata metadata, MarketType marketType,
                                      String symbolScope, String channel, String contentType, String payload) {
        return new RawMarketMessage(metadata, marketType, symbolScope, channel, contentType,
                payload, sha256(payload));
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
