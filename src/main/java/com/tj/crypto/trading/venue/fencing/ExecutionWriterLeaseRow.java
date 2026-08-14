package com.tj.crypto.trading.venue.fencing;

import lombok.Data;

/** Database-clock snapshot of the global private-venue writer lease. */
@Data
public class ExecutionWriterLeaseRow {
    private String leaseScope;
    private String ownerId;
    private long fencingToken;
    private long leaseUntilMs;
    private long heartbeatAtMs;
    private long databaseNowMs;
}
