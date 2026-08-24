package com.tj.crypto.autonomy.execution;

/**
 * Isolation seam for a future non-exportable signing service.
 *
 * <p>No signing method accepts an {@link ExecutionCandidate}; a later OMS-owned opaque command is
 * required before this port can be evolved.
 */
public interface IsolatedExecutionSignerPort {
    IsolatedPortAvailability availability();
}
