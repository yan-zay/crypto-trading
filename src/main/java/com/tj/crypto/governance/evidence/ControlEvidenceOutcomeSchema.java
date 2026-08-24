package com.tj.crypto.governance.evidence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Versioned minimum signed outcome/measure/threshold contract for every readiness control. */
final class ControlEvidenceOutcomeSchema {
    private ControlEvidenceOutcomeSchema() {
    }

    static List<String> verify(ControlKind kind, Map<String, String> claims) {
        List<String> blockers = new ArrayList<>();
        String expectedSchema = "governance-control-evidence/"
                + kind.name().toLowerCase(Locale.ROOT) + "/v1";
        requireEqual(claims, "schemaVersion", expectedSchema, blockers);
        requireEqual(claims, "status", EvidenceStatus.VERIFIED.name(), blockers);
        requireEqual(claims, "outcome",
                kind == ControlKind.LEGAL_APPROVAL ? "APPROVED" : "PASS", blockers);

        switch (kind) {
            case LEGAL_APPROVAL -> {
                requireEqual(claims, "measure.decision", "APPROVED", blockers);
                requireEqual(claims, "threshold.requiredDecision", "APPROVED", blockers);
            }
            case KMS_KEY_CUSTODY -> {
                requireEqual(claims, "measure.nonExportableKeyVerified", "true", blockers);
                requireEqual(claims, "threshold.requireNonExportableKey", "true", blockers);
            }
            case MFA -> atLeast(claims, "measure.successfulChallenges",
                    "threshold.minimumSuccessfulChallenges", 1, blockers);
            case DUAL_APPROVAL -> atLeast(claims, "measure.distinctApprovers",
                    "threshold.minimumDistinctApprovers", 2, blockers);
            case IP_ALLOWLIST -> atMost(claims, "measure.unauthorizedSourceCount",
                    "threshold.maximumUnauthorizedSourceCount", 0, blockers);
            case EXTERNAL_ALERT_DELIVERY -> atLeast(claims, "measure.deliveredAlertCount",
                    "threshold.minimumDeliveredAlertCount", 1, blockers);
            case BACKUP_PITR_RESTORE -> atLeast(claims, "measure.successfulRestoreCount",
                    "threshold.minimumSuccessfulRestoreCount", 1, blockers);
            case RPO_RTO -> {
                atMostPositiveThreshold(claims, "measure.rpoMs", "threshold.maximumRpoMs",
                        blockers);
                atMostPositiveThreshold(claims, "measure.rtoMs", "threshold.maximumRtoMs",
                        blockers);
            }
            case VENUE_DEMO_CONTRACT -> {
                atLeast(claims, "measure.passedScenarioCount",
                        "threshold.requiredScenarioCount", 1, blockers);
                atMost(claims, "measure.failedScenarioCount",
                        "threshold.maximumFailedScenarioCount", 0, blockers);
            }
            case PRIVATE_STREAM_STABILITY -> atLeast(claims, "measure.connectedDurationMs",
                    "threshold.minimumConnectedDurationMs", Duration.ofDays(7).toMillis(), blockers);
            case SOAK_24H -> atLeast(claims, "measure.observedDurationMs",
                    "threshold.minimumObservedDurationMs", Duration.ofHours(24).toMillis(), blockers);
            case SOAK_7D -> atLeast(claims, "measure.observedDurationMs",
                    "threshold.minimumObservedDurationMs", Duration.ofDays(7).toMillis(), blockers);
            case CHAOS -> {
                atLeast(claims, "measure.chaosEventCount",
                        "threshold.minimumChaosEventCount", 1, blockers);
                atMost(claims, "measure.unrecoveredIncidentCount",
                        "threshold.maximumUnrecoveredIncidentCount", 0, blockers);
            }
            case FORWARD_PAPER_30D -> atLeast(claims, "measure.observedDurationMs",
                    "threshold.minimumObservedDurationMs", Duration.ofDays(30).toMillis(), blockers);
        }
        return blockers;
    }

    private static void requireEqual(Map<String, String> claims, String name, String expected,
                                     List<String> blockers) {
        if (!expected.equals(claims.get(name))) {
            blockers.add("signed control claim mismatch: " + name);
        }
    }

    private static void atLeast(Map<String, String> claims, String measureName,
                                String thresholdName, long policyFloor,
                                List<String> blockers) {
        Long measure = nonNegativeLong(claims.get(measureName));
        Long threshold = nonNegativeLong(claims.get(thresholdName));
        if (measure == null) blockers.add("signed control claim is invalid: " + measureName);
        if (threshold == null || threshold < policyFloor) {
            blockers.add("signed control threshold is invalid: " + thresholdName);
        } else if (measure != null && measure < threshold) {
            blockers.add("signed control outcome misses threshold: " + measureName);
        }
    }

    private static void atMost(Map<String, String> claims, String measureName,
                               String thresholdName, long policyCeiling,
                               List<String> blockers) {
        Long measure = nonNegativeLong(claims.get(measureName));
        Long threshold = nonNegativeLong(claims.get(thresholdName));
        if (measure == null) blockers.add("signed control claim is invalid: " + measureName);
        if (threshold == null || threshold > policyCeiling) {
            blockers.add("signed control threshold is invalid: " + thresholdName);
        } else if (measure != null && measure > threshold) {
            blockers.add("signed control outcome misses threshold: " + measureName);
        }
    }

    private static void atMostPositiveThreshold(Map<String, String> claims, String measureName,
                                                String thresholdName, List<String> blockers) {
        Long measure = nonNegativeLong(claims.get(measureName));
        Long threshold = nonNegativeLong(claims.get(thresholdName));
        if (measure == null) blockers.add("signed control claim is invalid: " + measureName);
        if (threshold == null || threshold <= 0) {
            blockers.add("signed control threshold is invalid: " + thresholdName);
        } else if (measure != null && measure > threshold) {
            blockers.add("signed control outcome misses threshold: " + measureName);
        }
    }

    private static Long nonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? null : parsed;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
