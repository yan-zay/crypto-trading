package com.tj.crypto.autonomy.execution;

/** Safe default until an owner-operated, evidence-producing adapter is connected. */
public final class RejectingVenueExecutionCapabilityVerifier
        implements VenueExecutionCapabilityVerifier {
    @Override
    public VenueExecutionCapabilityVerification verify(VenueExecutionCapabilityRequest request) {
        return VenueExecutionCapabilityVerification.notWired();
    }
}
