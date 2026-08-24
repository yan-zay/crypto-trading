package com.tj.crypto.autonomy.execution;

/** Default execution boundary. A real broker or venue adapter is deliberately absent. */
public final class UnavailableVenueExecutorPort implements IsolatedVenueExecutorPort {
    @Override
    public IsolatedPortAvailability availability() {
        return IsolatedPortAvailability.notWired("EXECUTOR_NOT_WIRED");
    }
}
