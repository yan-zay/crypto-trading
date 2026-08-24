package com.tj.crypto.autonomy.operations;

/**
 * Durable single-head boundary. Implementations must use one transaction/linearizable operation for
 * compare-and-set, append the full snapshot and reject a consumed or stale predecessor.
 */
public interface DurableOperationsHeadPort {
    DurableHeadVerification verifyCurrent(OperationsSnapshot snapshot, long nowMs);

    OperationsHeadCasResult compareAndSet(String expectedHeadSha256,
                                          long expectedRevision,
                                          OperationsSnapshot result,
                                          java.util.List<OperationsActionIntent> intents);

    static DurableOperationsHeadPort failClosed() {
        return new DurableOperationsHeadPort() {
            @Override
            public DurableHeadVerification verifyCurrent(OperationsSnapshot snapshot, long nowMs) {
                return DurableHeadVerification.untrusted("durable operations head is not configured");
            }

            @Override
            public OperationsHeadCasResult compareAndSet(String expectedHeadSha256,
                                                         long expectedRevision,
                                                         OperationsSnapshot result,
                                                         java.util.List<OperationsActionIntent> intents) {
                return OperationsHeadCasResult.notApplied(
                        "durable operations head is not configured");
            }
        };
    }
}
