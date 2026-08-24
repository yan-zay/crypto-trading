package com.tj.crypto.autonomy.operations;

/** External human identity, signature and anti-replay boundary for recovery completion. */
@FunctionalInterface
public interface RecoveryCompletionVerifier {
    RecoveryCompletionVerification verify(RecoveryCompletionCommand command,
                                          RecoveryRequest request,
                                          long nowMs);
}
