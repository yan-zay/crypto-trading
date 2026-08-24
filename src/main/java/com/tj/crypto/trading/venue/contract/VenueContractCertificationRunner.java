package com.tj.crypto.trading.venue.contract;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Orchestrates all mandatory scenarios and never turns offline or missing-credential evidence green. */
public final class VenueContractCertificationRunner {
    private final Clock clock;
    private final CredentialAttestationVerifier credentialVerifier;
    private final VenueContractObservationEvaluator evaluator = new VenueContractObservationEvaluator();

    public VenueContractCertificationRunner(Clock clock) {
        this(clock, CredentialAttestationVerifier.unconfigured());
    }

    public VenueContractCertificationRunner(Clock clock,
                                            CredentialAttestationVerifier credentialVerifier) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.credentialVerifier = java.util.Objects.requireNonNull(
                credentialVerifier, "credentialVerifier");
    }

    public VenueContractCertificationReport certify(VenueContractProbe probe) {
        if (probe == null) throw new IllegalArgumentException("probe is required");
        long now = clock.millis();
        VenueContractProfile profile = java.util.Objects.requireNonNull(probe.profile(), "profile");
        VenueContractProbe.Kind probeKind = java.util.Objects.requireNonNull(
                probe.kind(), "probe.kind");
        CredentialAttestation credential = java.util.Objects.requireNonNull(
                probe.credentialAttestation(), "credentialAttestation");
        List<VenueContractCheckResult> checks = new ArrayList<>();

        List<String> profileViolations = new ArrayList<>(profile.boundaryViolations());
        if (probeKind == VenueContractProbe.Kind.OWNER_REMOTE
                && !profile.ownerControlledEnvironment()) {
            profileViolations.add("OWNER_REMOTE probes require an owner-controlled environment");
        }
        VenueContractCheckResult.Status boundaryStatus = profileViolations.isEmpty()
                ? passStatus(probeKind) : VenueContractCheckResult.Status.FAILED;
        checks.add(result(VenueContractScenario.PROFILE_BOUNDARY, boundaryStatus, now,
                "Static owner/account/endpoint/permission boundary validation",
                profileViolations, profile.canonicalForm() + "|probeKind=" + probeKind));

        CredentialAttestationVerifier.Decision credentialDecision =
                verifyCredential(probeKind, profile, credential, now);
        for (VenueContractScenario scenario : VenueContractScenario.values()) {
            if (scenario == VenueContractScenario.PROFILE_BOUNDARY) continue;
            if (!profileViolations.isEmpty()) {
                checks.add(notVerified(scenario, now,
                        "Unsafe or incomplete profile prevents network probes"));
                continue;
            }
            if (probeKind == VenueContractProbe.Kind.OWNER_REMOTE
                    && credentialDecision.status()
                    != CredentialAttestationVerifier.Status.VERIFIED) {
                if (credentialDecision.status() == CredentialAttestationVerifier.Status.INVALID) {
                    checks.add(failed(scenario, now, credentialDecision.reason()));
                } else {
                    checks.add(notVerified(scenario, now, credentialDecision.reason()));
                }
                continue;
            }
            checks.add(execute(probe, probeKind, scenario, now));
        }
        checks.sort(Comparator.comparingInt(check -> check.scenario().ordinal()));
        VenueContractCertificationReport.Status aggregate = aggregate(
                profile, probeKind, checks);
        String reportHash = reportHash(profile, probeKind, now, aggregate, checks);
        return new VenueContractCertificationReport(
                profile, probeKind, now, aggregate, checks, reportHash);
    }

    private CredentialAttestationVerifier.Decision verifyCredential(
            VenueContractProbe.Kind probeKind, VenueContractProfile profile,
            CredentialAttestation credential, long now) {
        if (probeKind == VenueContractProbe.Kind.OFFLINE_REPLAY) {
            return CredentialAttestationVerifier.Decision.notVerified(
                    "Offline replay cannot verify owner credentials");
        }
        try {
            return java.util.Objects.requireNonNull(
                    credentialVerifier.verify(profile, credential, now),
                    "credential verifier decision");
        } catch (RuntimeException e) {
            return CredentialAttestationVerifier.Decision.invalid("credential-verifier",
                    "Trusted credential verifier failed closed");
        }
    }

    private VenueContractCheckResult execute(VenueContractProbe probe,
                                             VenueContractProbe.Kind probeKind,
                                             VenueContractScenario scenario,
                                             long now) {
        try {
            VenueContractObservation observation = probe.observe(scenario);
            if (observation == null || observation.scenario() != scenario) {
                return failed(scenario, now, "Probe returned no matching observation");
            }
            VenueContractObservationEvaluator.Evaluation evaluation = evaluator.evaluate(observation);
            VenueContractCheckResult.Status status = evaluation.passed()
                    ? passStatus(probeKind) : VenueContractCheckResult.Status.FAILED;
            return new VenueContractCheckResult(scenario, status, observation.observedAtMs(),
                    observation.rawEvidenceSha256(), observation.summary(), evaluation.blockers());
        } catch (RuntimeException e) {
            // Exception messages may contain signed URLs or venue payloads, so they are never copied.
            return failed(scenario, now, "Probe failed; details were intentionally redacted");
        }
    }

    private VenueContractCheckResult.Status passStatus(VenueContractProbe.Kind probeKind) {
        return probeKind == VenueContractProbe.Kind.OWNER_REMOTE
                ? VenueContractCheckResult.Status.VERIFIED
                : VenueContractCheckResult.Status.OFFLINE_PASSED;
    }

    private VenueContractCheckResult notVerified(VenueContractScenario scenario,
                                                 long now, String blocker) {
        return result(scenario, VenueContractCheckResult.Status.NOT_VERIFIED, now,
                "Owner demo/testnet evidence is NOT_VERIFIED", List.of(blocker), blocker);
    }

    private VenueContractCheckResult failed(VenueContractScenario scenario,
                                            long now, String blocker) {
        return result(scenario, VenueContractCheckResult.Status.FAILED, now,
                "Venue contract check failed", List.of(blocker), blocker);
    }

    private VenueContractCheckResult result(VenueContractScenario scenario,
                                            VenueContractCheckResult.Status status,
                                            long now, String summary,
                                            List<String> blockers, String evidence) {
        return new VenueContractCheckResult(scenario, status, now,
                VenueContractEvidenceDigest.sha256(scenario + "|" + status + "|" + evidence),
                summary, blockers);
    }

    private VenueContractCertificationReport.Status aggregate(
            VenueContractProfile profile, VenueContractProbe.Kind probeKind,
            List<VenueContractCheckResult> checks) {
        if (checks.stream().anyMatch(check -> check.status()
                == VenueContractCheckResult.Status.FAILED)) {
            return VenueContractCertificationReport.Status.FAILED;
        }
        if (probeKind == VenueContractProbe.Kind.OWNER_REMOTE
                && profile.ownerControlledEnvironment() && checks.stream().allMatch(check ->
                check.status() == VenueContractCheckResult.Status.VERIFIED)) {
            return VenueContractCertificationReport.Status.VERIFIED;
        }
        return VenueContractCertificationReport.Status.NOT_VERIFIED;
    }

    private String reportHash(VenueContractProfile profile, VenueContractProbe.Kind probeKind,
                              long now,
                              VenueContractCertificationReport.Status status,
                              List<VenueContractCheckResult> checks) {
        StringBuilder canonical = new StringBuilder()
                .append(profile.canonicalForm()).append('|').append(probeKind)
                .append('|').append(now).append('|').append(status);
        for (VenueContractCheckResult check : checks) {
            canonical.append('|').append(check.scenario()).append(':').append(check.status())
                    .append(':').append(check.evidenceSha256());
        }
        return VenueContractEvidenceDigest.sha256(canonical.toString());
    }
}
