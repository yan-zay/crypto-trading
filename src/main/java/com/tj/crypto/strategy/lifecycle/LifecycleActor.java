package com.tj.crypto.strategy.lifecycle;

import java.util.Set;

public record LifecycleActor(String operator, Set<LifecyclePermission> permissions) {
    public LifecycleActor {
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("operator is required");
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean can(LifecyclePermission permission) {
        return permissions.contains(permission);
    }

    public static LifecycleActor policyGate() {
        return new LifecycleActor("strategy-policy-gate", Set.of(LifecyclePermission.ENFORCE_POLICY));
    }
}
