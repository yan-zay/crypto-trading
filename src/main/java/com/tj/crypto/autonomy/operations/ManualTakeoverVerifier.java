package com.tj.crypto.autonomy.operations;

/** External trust boundary for manual takeover authentication and replay reservation. */
@FunctionalInterface
public interface ManualTakeoverVerifier {
    ManualTakeoverVerification verify(ManualTakeoverCommand command, long nowMs);
}
