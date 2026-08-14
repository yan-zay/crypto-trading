package com.tj.crypto.risk;

/** Durable kill-switch snapshot shared across process restarts. */
public record KillSwitchState(
        KillSwitch.Mode mode,
        String reason,
        String changedBy,
        long changedAtMs,
        long version
) {
    public KillSwitchState {
        if (mode == null) throw new IllegalArgumentException("Kill-switch mode is required");
        reason = reason == null || reason.isBlank() ? "UNSPECIFIED" : reason;
        changedBy = changedBy == null || changedBy.isBlank() ? "SYSTEM" : changedBy;
    }
}
