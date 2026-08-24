package com.tj.crypto.research.agent.sandbox;

/** 沙盒工具超过确定性时间预算。 */
public class SandboxTimeoutException extends IllegalStateException {
    public SandboxTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
