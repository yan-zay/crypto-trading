package com.tj.crypto.governance.security;

import java.util.List;

public record KeyCustodyDecision(boolean approved, List<String> blockers) {
    public KeyCustodyDecision {
        blockers = List.copyOf(blockers);
        if (approved && !blockers.isEmpty()) {
            throw new IllegalArgumentException("approved custody cannot contain blockers");
        }
    }
}
