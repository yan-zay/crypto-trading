package com.tj.crypto.autonomy.execution;

/** Read-only declaration; it carries no credentials and grants no operation. */
public record IsolatedPortAvailability(boolean available, String reasonCode) {
    public IsolatedPortAvailability {
        reasonCode = ExecutionValues.id(reasonCode, "reasonCode", 96);
    }

    public static IsolatedPortAvailability notWired(String reasonCode) {
        return new IsolatedPortAvailability(false, reasonCode);
    }
}
