package com.tj.crypto.autonomy.execution;

/** Read-only isolation seam; this package exposes no dispatch operation. */
public interface IsolatedVenueExecutorPort {
    IsolatedPortAvailability availability();
}
