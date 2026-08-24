package com.tj.crypto.research.agent.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.research.dataset.ResearchJson;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class AgentEvalHashing {
    static final String SHA_256 = "SHA-256";
    static final String LOWERCASE_SHA256 = "[0-9a-f]{64}";

    private AgentEvalHashing() {}

    static ObjectMapper mapper() {
        return ResearchJson.mapper();
    }

    static byte[] canonicalBytes(Object value) {
        try {
            return mapper().writeValueAsBytes(value);
        } catch (JsonProcessingException error) {
            throw new AgentEvalException("CANONICALIZATION_FAILED",
                    "Cannot serialize canonical Agent evaluation evidence", error);
        }
    }

    static String sha256Canonical(Object value) {
        return sha256(canonicalBytes(value));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256).digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String requireSha256(String value, String name) {
        if (value == null || !value.matches(LOWERCASE_SHA256)) {
            throw new AgentEvalException("INVALID_CONTENT_DIGEST", name + " must be a lowercase SHA-256");
        }
        return value;
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AgentEvalException("MISSING_EVALUATION_FIELD", name + " is required");
        }
        return value;
    }
}
