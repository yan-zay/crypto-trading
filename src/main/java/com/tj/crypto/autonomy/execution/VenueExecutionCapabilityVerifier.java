package com.tj.crypto.autonomy.execution;

/** Trust boundary for venue rules, trading calendars and valuation semantics. */
@FunctionalInterface
public interface VenueExecutionCapabilityVerifier {
    VenueExecutionCapabilityVerification verify(VenueExecutionCapabilityRequest request);
}
