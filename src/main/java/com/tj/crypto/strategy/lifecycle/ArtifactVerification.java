package com.tj.crypto.strategy.lifecycle;

public record ArtifactVerification(boolean valid, LifecycleReason reason, String detail) {
    public static ArtifactVerification accepted() {
        return new ArtifactVerification(true, LifecycleReason.ACCEPTED, "artifact signature verified");
    }

    public static ArtifactVerification denied(LifecycleReason reason, String detail) {
        return new ArtifactVerification(false, reason, detail);
    }
}
