package com.tj.crypto.autonomy.execution;

/** Default signer boundary. Production signing is deliberately absent. */
public final class UnavailableExecutionSignerPort implements IsolatedExecutionSignerPort {
    @Override
    public IsolatedPortAvailability availability() {
        return IsolatedPortAvailability.notWired("SIGNER_NOT_WIRED");
    }
}
