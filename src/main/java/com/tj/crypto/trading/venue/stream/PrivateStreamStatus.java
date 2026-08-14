package com.tj.crypto.trading.venue.stream;

public record PrivateStreamStatus(String exchange, String channel, boolean enabled,
                                  boolean connected, long lastMessageAtMs, String lastError) {}
