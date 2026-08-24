package com.tj.crypto.research.agent.eval;

/** Fail-closed error raised when Agent evaluation evidence is malformed or mutable. */
public final class AgentEvalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public AgentEvalException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("evaluation error code is required");
        }
        this.code = code;
    }

    public AgentEvalException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("evaluation error code is required");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
