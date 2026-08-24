package com.tj.crypto.autonomy.canary;

import java.time.Instant;

@FunctionalInterface
public interface CapitalApprovalVerifier {
    CapitalApprovalVerification verify(CapitalStepApproval approval, Instant now);
}
