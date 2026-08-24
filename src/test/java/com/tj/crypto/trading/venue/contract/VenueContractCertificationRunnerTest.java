package com.tj.crypto.trading.venue.contract;

import com.tj.crypto.common.domain.Exchange;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.tj.crypto.trading.venue.contract.VenueContractAction.Outcome.ACCEPTED;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Outcome.AMBIGUOUS;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Outcome.CANCELLED;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Outcome.COMPLETED;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Outcome.CONNECTED;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Outcome.DISCONNECTED;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Outcome.KNOWN;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Type.BACKFILL;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Type.CANCEL;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Type.PLACE;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Type.QUERY;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Type.STREAM_DISCONNECT;
import static com.tj.crypto.trading.venue.contract.VenueContractAction.Type.STREAM_RECONNECT;
import static org.assertj.core.api.Assertions.assertThat;

class VenueContractCertificationRunnerTest {
    private static final long NOW = 1_787_184_000_000L;
    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1_000;
    private static final String OWNER_ISSUER = "owner-evidence-signer-v1";
    private static final KeyPair OWNER_KEY_PAIR = generateOwnerKeyPair();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Test
    void offlineCorpusCanValidateTheHarnessButCanNeverVerifyOwnerCertification() {
        FixtureProbe probe = FixtureProbe.offline();

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.NOT_VERIFIED);
        assertThat(report.eligibleForPromotion()).isFalse();
        assertThat(report.checks()).hasSize(VenueContractScenario.values().length)
                .allMatch(check -> check.status()
                        == VenueContractCheckResult.Status.OFFLINE_PASSED);
        assertThat(probe.observationCalls).isEqualTo(VenueContractScenario.values().length - 1);
    }

    @Test
    void missingOwnerCredentialAttestationIsExplicitlyNotVerifiedAndMakesNoProbeCalls() {
        CountingUnavailableProbe probe = new CountingUnavailableProbe(ownerProfile());

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.NOT_VERIFIED);
        assertThat(report.checks()).filteredOn(check -> check.scenario()
                        != VenueContractScenario.PROFILE_BOUNDARY)
                .allMatch(check -> check.status()
                        == VenueContractCheckResult.Status.NOT_VERIFIED)
                .allSatisfy(check -> assertThat(check.blockers())
                        .containsExactly("Owner testnet credentials were not supplied"));
        assertThat(probe.observationCalls).isZero();
    }

    @Test
    void completeOwnerTestnetEvidenceCanBeVerified() {
        FixtureProbe probe = FixtureProbe.owner();

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.VERIFIED);
        assertThat(report.eligibleForPromotion()).isTrue();
        assertThat(report.checks()).allMatch(check ->
                check.status() == VenueContractCheckResult.Status.VERIFIED);
    }

    @Test
    void ownerClaimWithoutASeparatelyConfiguredVerifierRemainsNotVerified() {
        FixtureProbe probe = FixtureProbe.owner();

        VenueContractCertificationReport report =
                new VenueContractCertificationRunner(clock).certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.NOT_VERIFIED);
        assertThat(report.probeKind()).isEqualTo(VenueContractProbe.Kind.OWNER_REMOTE);
        assertThat(report.checks()).filteredOn(check -> check.scenario()
                        != VenueContractScenario.PROFILE_BOUNDARY)
                .allMatch(check -> check.status()
                        == VenueContractCheckResult.Status.NOT_VERIFIED);
        assertThat(probe.observationCalls).isZero();
    }

    @Test
    void offlineProbeCannotBecomeVerifiedByWritingAnOwnerProfile() {
        FixtureProbe probe = FixtureProbe.ownerProfileOfflineReplay();

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.NOT_VERIFIED);
        assertThat(report.probeKind()).isEqualTo(VenueContractProbe.Kind.OFFLINE_REPLAY);
        assertThat(report.checks()).allMatch(check ->
                check.status() == VenueContractCheckResult.Status.OFFLINE_PASSED);
        assertThat(report.eligibleForPromotion()).isFalse();
    }

    @Test
    void profileBoundSignatureCannotBeReusedForAnotherAccount() {
        VenueContractProfile original = ownerProfile();
        CredentialAttestation originalAttestation = signedCredential(original);
        VenueContractProfile differentAccount = new VenueContractProfile(
                original.owner(), original.exchange(), original.environment(), "other-account",
                original.product(), original.accountMode(), original.marginMode(),
                original.restEndpoint(), original.privateStreamEndpoint(),
                original.simulatedTradingHeader(), original.permissions(),
                original.withdrawalDisabled(), original.networkEgressAllowlistEvidence());
        FixtureProbe probe = new FixtureProbe(differentAccount, originalAttestation,
                VenueContractProbe.Kind.OWNER_REMOTE);

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.FAILED);
        assertThat(report.checks()).filteredOn(check -> check.scenario()
                        != VenueContractScenario.PROFILE_BOUNDARY)
                .allMatch(check -> check.status() == VenueContractCheckResult.Status.FAILED);
        assertThat(probe.observationCalls).isZero();
    }

    @Test
    void tamperedOwnerSignatureFailsClosedBeforeAnyProbeCall() {
        VenueContractProfile profile = ownerProfile();
        CredentialAttestation signed = signedCredential(profile);
        CredentialAttestation tampered = CredentialAttestation.signedAvailable(
                signed.issuer(), signed.profileSha256(), signed.evidenceReference(),
                signed.attestedAtMs(), signed.expiresAtMs(), Base64.getEncoder()
                        .encodeToString(new byte[64]));
        FixtureProbe probe = new FixtureProbe(profile, tampered,
                VenueContractProbe.Kind.OWNER_REMOTE);

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.FAILED);
        assertThat(report.checks()).filteredOn(check -> check.scenario()
                        != VenueContractScenario.PROFILE_BOUNDARY)
                .allMatch(check -> check.status() == VenueContractCheckResult.Status.FAILED);
        assertThat(probe.observationCalls).isZero();
    }

    @Test
    void productionEndpointInATestnetProfileFailsClosedBeforeCallingTheProbe() {
        VenueContractProfile unsafe = new VenueContractProfile(
                "owner-team", Exchange.BINANCE,
                VenueContractProfile.Environment.BINANCE_TESTNET,
                "sandbox-account", "PERPETUAL",
                VenueContractProfile.AccountMode.ONE_WAY,
                VenueContractProfile.MarginMode.ISOLATED,
                URI.create("https://api.binance.com"),
                URI.create("wss://fstream.binance.com/ws"), false,
                requiredPermissions(), true, "egress-attestation-1");
        FixtureProbe probe = FixtureProbe.owner(unsafe);

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.FAILED);
        assertThat(report.checks().get(0).blockers())
                .anyMatch(reason -> reason.contains("outside the approved sandbox allowlist"));
        assertThat(probe.observationCalls).isZero();
    }

    @Test
    void okxDemoWithoutSimulatedTradingHeaderFailsClosed() {
        VenueContractProfile unsafe = new VenueContractProfile(
                "owner-team", Exchange.OKX, VenueContractProfile.Environment.OKX_DEMO,
                "sandbox-account", "PERPETUAL", VenueContractProfile.AccountMode.HEDGE,
                VenueContractProfile.MarginMode.CROSS, URI.create("https://www.okx.com"),
                URI.create("wss://wspap.okx.com/ws/v5/private"), false,
                requiredPermissions(), true, "egress-attestation-1");
        FixtureProbe probe = FixtureProbe.owner(unsafe);

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.FAILED);
        assertThat(report.checks().get(0).blockers())
                .contains("OKX demo requires the simulated-trading header");
        assertThat(probe.observationCalls).isZero();
    }

    @Test
    void endpointQueryAndNonTlsPortCannotCarryCredentialMaterial() {
        VenueContractProfile unsafe = new VenueContractProfile(
                "owner-team", Exchange.BINANCE,
                VenueContractProfile.Environment.BINANCE_TESTNET,
                "sandbox-account", "PERPETUAL", VenueContractProfile.AccountMode.ONE_WAY,
                VenueContractProfile.MarginMode.ISOLATED,
                URI.create("https://testnet.binancefuture.com:8443?apiKey=forbidden"),
                URI.create("wss://fstream.binancefuture.com/ws"), false,
                requiredPermissions(), true, "egress-attestation-1");
        FixtureProbe probe = FixtureProbe.owner(unsafe);

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.FAILED);
        assertThat(report.checks().get(0).blockers())
                .contains("REST endpoint is not a credential-free HTTPS URI");
        assertThat(probe.observationCalls).isZero();
    }

    @Test
    void placeQueryCancelRequiresSuccessfulOutcomesNotOnlyActionNames() {
        FixtureProbe probe = FixtureProbe.owner();
        probe.observations.put(VenueContractScenario.PLACE_QUERY_CANCEL,
                observation(VenueContractScenario.PLACE_QUERY_CANCEL, 25,
                        allTrue("sameClientOrderId", "finalStateCancelled"),
                        List.of(action(1, PLACE, VenueContractAction.Outcome.REJECTED),
                                action(2, QUERY, KNOWN), action(3, CANCEL, CANCELLED),
                                action(4, QUERY, CANCELLED))));

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(result(report, VenueContractScenario.PLACE_QUERY_CANCEL).blockers())
                .contains("Required action/outcome sequence was not observed");
        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.FAILED);
    }

    @Test
    void unknownOutcomeFailsWhenTheDriverPlacesAgainInsteadOfOnlyQuerying() {
        FixtureProbe probe = FixtureProbe.owner();
        probe.observations.put(VenueContractScenario.UNKNOWN_QUERY_ONLY,
                observation(VenueContractScenario.UNKNOWN_QUERY_ONLY, 25,
                        Map.of("clientOrderIdStable", "true", "unresolvedRiskFrozen", "true"),
                        List.of(action(1, PLACE, AMBIGUOUS), action(2, QUERY, AMBIGUOUS),
                                action(3, PLACE, ACCEPTED))));

        VenueContractCertificationReport report = runner().certify(probe);
        VenueContractCheckResult result = result(report, VenueContractScenario.UNKNOWN_QUERY_ONLY);

        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.FAILED);
        assertThat(result.status()).isEqualTo(VenueContractCheckResult.Status.FAILED);
        assertThat(result.blockers()).contains(
                "UNKNOWN scenario requires exactly one ambiguous place attempt");
    }

    @Test
    void incompleteTruthCapabilityAndShortStabilityWindowCannotPass() {
        FixtureProbe probe = FixtureProbe.owner();
        probe.observations.put(VenueContractScenario.ACTIVE_ORDERS,
                observation(VenueContractScenario.ACTIVE_ORDERS, 10,
                        Map.of("capabilitySupported", "false",
                                "paginationCovered", "true",
                                "emptyDistinguishedFromUnsupported", "true"), List.of()));
        probe.observations.put(VenueContractScenario.PRIVATE_STREAM_SEVEN_DAY_STABILITY,
                observation(VenueContractScenario.PRIVATE_STREAM_SEVEN_DAY_STABILITY,
                        SEVEN_DAYS_MS - 1,
                        allTrue("wallClockElapsed", "noUnexplainedGaps",
                                "noUnboundedGrowth", "reconciliationStable"), List.of()));

        VenueContractCertificationReport report = runner().certify(probe);

        assertThat(result(report, VenueContractScenario.ACTIVE_ORDERS).blockers())
                .contains("Missing verified fact: capabilitySupported");
        assertThat(result(report, VenueContractScenario.PRIVATE_STREAM_SEVEN_DAY_STABILITY)
                .blockers()).contains("A real seven-day wall-clock observation was not supplied");
        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.FAILED);
    }

    @Test
    void probeExceptionsAreRedactedAndFailTheReport() {
        FixtureProbe delegate = FixtureProbe.owner();
        VenueContractProbe probe = new VenueContractProbe() {
            @Override public Kind kind() { return delegate.kind(); }
            @Override public VenueContractProfile profile() { return delegate.profile(); }
            @Override public CredentialAttestation credentialAttestation() {
                return delegate.credentialAttestation();
            }
            @Override public VenueContractObservation observe(VenueContractScenario scenario) {
                if (scenario == VenueContractScenario.ERROR_MAPPING) {
                    throw new IllegalStateException("apiKey=SHOULD_NEVER_APPEAR");
                }
                return delegate.observe(scenario);
            }
        };

        VenueContractCertificationReport report = runner().certify(probe);
        VenueContractCheckResult failed = result(report, VenueContractScenario.ERROR_MAPPING);

        assertThat(failed.status()).isEqualTo(VenueContractCheckResult.Status.FAILED);
        assertThat(failed.summary() + failed.blockers()).doesNotContain("SHOULD_NEVER_APPEAR");
    }

    @Test
    void fixedInputsProduceTheSameContentAddressedReport() {
        VenueContractCertificationReport first = runner().certify(FixtureProbe.owner());
        VenueContractCertificationReport second = runner().certify(FixtureProbe.owner());

        assertThat(first.reportSha256()).isEqualTo(second.reportSha256())
                .matches("[0-9a-f]{64}");
    }

    private VenueContractCertificationRunner runner() {
        return new VenueContractCertificationRunner(clock,
                new Ed25519CredentialAttestationVerifier(
                        Map.of(OWNER_ISSUER, OWNER_KEY_PAIR.getPublic())));
    }

    private static VenueContractCheckResult result(VenueContractCertificationReport report,
                                                   VenueContractScenario scenario) {
        return report.checks().stream().filter(check -> check.scenario() == scenario)
                .findFirst().orElseThrow();
    }

    private static VenueContractProfile ownerProfile() {
        return new VenueContractProfile("owner-team", Exchange.BINANCE,
                VenueContractProfile.Environment.BINANCE_TESTNET,
                "sandbox-account", "PERPETUAL",
                VenueContractProfile.AccountMode.ONE_WAY,
                VenueContractProfile.MarginMode.ISOLATED,
                URI.create("https://testnet.binancefuture.com"),
                URI.create("wss://fstream.binancefuture.com/ws"), false,
                requiredPermissions(), true, "egress-attestation-1");
    }

    private static VenueContractProfile offlineProfile() {
        return new VenueContractProfile("offline-fixture", Exchange.BINANCE,
                VenueContractProfile.Environment.OFFLINE_FAKE,
                "fixture-account", "PERPETUAL",
                VenueContractProfile.AccountMode.ONE_WAY,
                VenueContractProfile.MarginMode.ISOLATED,
                URI.create("https://venue.fixture.invalid"),
                URI.create("wss://stream.fixture.invalid/ws"), false,
                requiredPermissions(), true, "fixture-egress-boundary");
    }

    private static EnumSet<VenueContractProfile.Permission> requiredPermissions() {
        return EnumSet.of(VenueContractProfile.Permission.READ_ACCOUNT,
                VenueContractProfile.Permission.READ_ORDERS,
                VenueContractProfile.Permission.READ_FILLS,
                VenueContractProfile.Permission.TRADE);
    }

    private static VenueContractObservation observation(VenueContractScenario scenario,
                                                        long durationMs,
                                                        Map<String, String> facts,
                                                        List<VenueContractAction> actions) {
        String evidence = VenueContractEvidenceDigest.sha256(
                scenario + "|" + durationMs + "|" + facts + "|" + actions);
        return new VenueContractObservation(scenario, NOW, durationMs,
                "fixture-v1", evidence, facts, actions, "Redacted fixture evidence for " + scenario);
    }

    private static VenueContractAction action(int sequence, VenueContractAction.Type type,
                                              VenueContractAction.Outcome outcome) {
        return new VenueContractAction(sequence, type, outcome, "client-order-1");
    }

    private static Map<String, String> allTrue(String... names) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String name : names) values.put(name, "true");
        return Map.copyOf(values);
    }

    private static final class FixtureProbe implements VenueContractProbe {
        private final VenueContractProfile profile;
        private final CredentialAttestation credential;
        private final Kind kind;
        private final EnumMap<VenueContractScenario, VenueContractObservation> observations;
        private int observationCalls;

        private FixtureProbe(VenueContractProfile profile, CredentialAttestation credential,
                             Kind kind) {
            this.profile = profile;
            this.credential = credential;
            this.kind = kind;
            this.observations = completeObservations();
        }

        static FixtureProbe offline() {
            return new FixtureProbe(offlineProfile(), CredentialAttestation.notApplicable(),
                    Kind.OFFLINE_REPLAY);
        }

        static FixtureProbe owner() {
            return owner(ownerProfile());
        }

        static FixtureProbe owner(VenueContractProfile profile) {
            return new FixtureProbe(profile, signedCredential(profile), Kind.OWNER_REMOTE);
        }

        static FixtureProbe ownerProfileOfflineReplay() {
            VenueContractProfile profile = ownerProfile();
            return new FixtureProbe(profile, signedCredential(profile), Kind.OFFLINE_REPLAY);
        }

        @Override public Kind kind() { return kind; }
        @Override public VenueContractProfile profile() { return profile; }
        @Override public CredentialAttestation credentialAttestation() { return credential; }
        @Override public VenueContractObservation observe(VenueContractScenario scenario) {
            observationCalls++;
            return observations.get(scenario);
        }

        private static EnumMap<VenueContractScenario, VenueContractObservation> completeObservations() {
            EnumMap<VenueContractScenario, VenueContractObservation> values =
                    new EnumMap<>(VenueContractScenario.class);
            values.put(VenueContractScenario.ACCOUNT_AND_PERMISSION_BOUNDARY,
                    observation(VenueContractScenario.ACCOUNT_AND_PERMISSION_BOUNDARY, 100,
                            allTrue("productMatched", "accountModeMatched", "marginModeMatched",
                                    "permissionsLeastPrivilegeVerified",
                                    "withdrawalDisabledVerified"), List.of()));
            values.put(VenueContractScenario.PLACE_QUERY_CANCEL,
                    observation(VenueContractScenario.PLACE_QUERY_CANCEL, 100,
                            allTrue("sameClientOrderId", "finalStateCancelled"),
                            List.of(action(1, PLACE, ACCEPTED), action(2, QUERY, KNOWN),
                                    action(3, CANCEL, CANCELLED), action(4, QUERY, CANCELLED))));
            values.put(VenueContractScenario.PARTIAL_FILL,
                    observation(VenueContractScenario.PARTIAL_FILL, 100,
                            allTrue("partialFillObserved", "cumulativeQuantityMonotonic",
                                    "fillDeduplicated"), List.of()));
            values.put(VenueContractScenario.PRECISION_AND_MIN_NOTIONAL,
                    observation(VenueContractScenario.PRECISION_AND_MIN_NOTIONAL, 100,
                            allTrue("invalidPrecisionRejected", "belowMinimumRejected",
                                    "validBoundaryAccepted", "filtersVersioned"), List.of()));
            values.put(VenueContractScenario.RATE_LIMIT,
                    observation(VenueContractScenario.RATE_LIMIT, 1_100,
                            allTrue("rateLimitObserved", "retryAfterHonored", "boundedBackoff",
                                    "requestBudgetEnforced"), List.of()));
            values.put(VenueContractScenario.ERROR_MAPPING,
                    observation(VenueContractScenario.ERROR_MAPPING, 100,
                            allTrue("authRejected", "validationRejected", "throttledMapped",
                                    "ambiguousServerFailureUnknown", "messagesRedacted"), List.of()));
            values.put(VenueContractScenario.CLOCK_SKEW,
                    observation(VenueContractScenario.CLOCK_SKEW, 100,
                            allTrue("skewDetected", "requestBlockedUntilSynchronized",
                                    "postSyncWithinTolerance"), List.of()));
            values.put(VenueContractScenario.PRIVATE_STREAM_RECONNECT,
                    observation(VenueContractScenario.PRIVATE_STREAM_RECONNECT, 1_000,
                            allTrue("disconnectDetected", "reconnected", "backfillTriggered",
                                    "listenKeyRenewedOrNotApplicable"),
                            List.of(action(1, STREAM_DISCONNECT, DISCONNECTED),
                                    action(2, STREAM_RECONNECT, CONNECTED),
                                    action(3, BACKFILL, COMPLETED))));
            values.put(VenueContractScenario.DUPLICATE_AND_DELAYED_EVENTS,
                    observation(VenueContractScenario.DUPLICATE_AND_DELAYED_EVENTS, 1_000,
                            allTrue("duplicateDeduplicated", "delayedDetected",
                                    "eventOrderPreserved", "gapFrozenOrBackfilled"), List.of()));
            values.put(VenueContractScenario.UNKNOWN_QUERY_ONLY,
                    observation(VenueContractScenario.UNKNOWN_QUERY_ONLY, 100,
                            allTrue("clientOrderIdStable", "unresolvedRiskFrozen"),
                            List.of(action(1, PLACE, AMBIGUOUS), action(2, QUERY, KNOWN))));
            for (VenueContractScenario scenario : List.of(VenueContractScenario.ACTIVE_ORDERS,
                    VenueContractScenario.RECENT_FILLS, VenueContractScenario.BALANCES,
                    VenueContractScenario.POSITIONS)) {
                values.put(scenario, observation(scenario, 100,
                        allTrue("capabilitySupported", "paginationCovered",
                                "emptyDistinguishedFromUnsupported"), List.of()));
            }
            values.put(VenueContractScenario.PRIVATE_STREAM_SEVEN_DAY_STABILITY,
                    observation(VenueContractScenario.PRIVATE_STREAM_SEVEN_DAY_STABILITY,
                            SEVEN_DAYS_MS, allTrue("wallClockElapsed", "noUnexplainedGaps",
                                    "noUnboundedGrowth", "reconciliationStable"), List.of()));
            return values;
        }
    }

    private static final class CountingUnavailableProbe implements VenueContractProbe {
        private final UnavailableVenueContractProbe delegate;
        private int observationCalls;

        private CountingUnavailableProbe(VenueContractProfile profile) {
            delegate = new UnavailableVenueContractProbe(profile,
                    "Owner testnet credentials were not supplied");
        }

        @Override public VenueContractProfile profile() { return delegate.profile(); }
        @Override public Kind kind() { return delegate.kind(); }
        @Override public CredentialAttestation credentialAttestation() {
            return delegate.credentialAttestation();
        }
        @Override public VenueContractObservation observe(VenueContractScenario scenario) {
            observationCalls++;
            return delegate.observe(scenario);
        }
    }

    private static CredentialAttestation signedCredential(VenueContractProfile profile) {
        long attestedAt = NOW - 1_000;
        long expiresAt = NOW + 60_000;
        String evidenceReference = "owner-vault-attestation-sha256:"
                + VenueContractEvidenceDigest.sha256("owner-attestation-fixture-v1");
        String payload = CredentialAttestation.signedPayload(OWNER_ISSUER,
                profile.profileSha256(), evidenceReference, attestedAt, expiresAt);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(OWNER_KEY_PAIR.getPrivate());
            signer.update(payload.getBytes(StandardCharsets.UTF_8));
            return CredentialAttestation.signedAvailable(OWNER_ISSUER,
                    profile.profileSha256(), evidenceReference, attestedAt, expiresAt,
                    Base64.getEncoder().encodeToString(signer.sign()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateOwnerKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
