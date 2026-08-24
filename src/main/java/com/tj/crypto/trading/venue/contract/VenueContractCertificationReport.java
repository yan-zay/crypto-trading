package com.tj.crypto.trading.venue.contract;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Complete certification report. OFFLINE_PASSED can never promote the aggregate to VERIFIED. */
public record VenueContractCertificationReport(
        VenueContractProfile profile,
        VenueContractProbe.Kind probeKind,
        long generatedAtMs,
        Status status,
        List<VenueContractCheckResult> checks,
        String reportSha256
) {
    public VenueContractCertificationReport {
        if (profile == null || probeKind == null || status == null) {
            throw new IllegalArgumentException("profile, probeKind and status are required");
        }
        if (generatedAtMs <= 0) throw new IllegalArgumentException("generatedAtMs must be positive");
        checks = checks == null ? List.of() : List.copyOf(checks);
        Set<VenueContractScenario> seen = new HashSet<>();
        for (VenueContractCheckResult check : checks) {
            if (!seen.add(check.scenario())) {
                throw new IllegalArgumentException("Duplicate contract check: " + check.scenario());
            }
        }
        if (!seen.equals(EnumSet.allOf(VenueContractScenario.class))) {
            throw new IllegalArgumentException("Every venue contract scenario must be reported exactly once");
        }
        if (!VenueContractEvidenceDigest.isSha256(reportSha256)) {
            throw new IllegalArgumentException("reportSha256 must be a SHA-256 digest");
        }
        boolean allVerified = checks.stream()
                .allMatch(check -> check.status() == VenueContractCheckResult.Status.VERIFIED);
        boolean anyFailed = checks.stream()
                .anyMatch(check -> check.status() == VenueContractCheckResult.Status.FAILED);
        if (status == Status.VERIFIED && (!allVerified || !profile.ownerControlledEnvironment()
                || probeKind != VenueContractProbe.Kind.OWNER_REMOTE)) {
            throw new IllegalArgumentException(
                    "VERIFIED requires an owner-remote probe and all checks to be verified");
        }
        if (status == Status.FAILED && !anyFailed) {
            throw new IllegalArgumentException("FAILED requires at least one failed check");
        }
    }

    public boolean eligibleForPromotion() {
        return status == Status.VERIFIED;
    }

    public enum Status { NOT_VERIFIED, VERIFIED, FAILED }
}
