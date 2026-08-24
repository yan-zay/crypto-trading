package com.tj.crypto.research.agent.sandbox;

import java.util.List;

final class SandboxValues {
    private SandboxValues() {
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        String normalized = value.trim();
        if (normalized.length() > 2000) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    static List<String> texts(List<String> values, String name) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException(name + " is required");
        List<String> normalized = values.stream().map(value -> text(value, name)).distinct().sorted().toList();
        if (normalized.size() > 50) throw new IllegalArgumentException(name + " has too many values");
        return normalized;
    }
}
