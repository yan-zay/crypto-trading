package com.tj.crypto.trading.venue.contract;

import com.tj.crypto.common.domain.Exchange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * External evidence sentinel. Replace the unavailable probe with an owner-managed driver only in
 * an isolated testnet job; missing credentials remain an explicit result rather than a skipped green.
 */
@Tag("external")
class OwnerVenueContractExternalTest {
    @Test
    void missingOwnerCredentialAndDriverRemainNotVerified() {
        VenueContractProfile profile = new VenueContractProfile(
                System.getenv().getOrDefault("VENUE_CONTRACT_OWNER", "NOT_CONFIGURED"),
                Exchange.BINANCE, VenueContractProfile.Environment.BINANCE_TESTNET,
                "owner-testnet-account", "PERPETUAL",
                VenueContractProfile.AccountMode.ONE_WAY,
                VenueContractProfile.MarginMode.ISOLATED,
                URI.create("https://testnet.binancefuture.com"),
                URI.create("wss://fstream.binancefuture.com/ws"), false,
                EnumSet.of(VenueContractProfile.Permission.READ_ACCOUNT,
                        VenueContractProfile.Permission.READ_ORDERS,
                        VenueContractProfile.Permission.READ_FILLS,
                        VenueContractProfile.Permission.TRADE),
                true, "owner-network-egress-evidence-NOT_SUPPLIED");
        VenueContractProbe unavailable = new UnavailableVenueContractProbe(profile,
                "Owner-managed credential attestation and contract driver are NOT_VERIFIED");

        VenueContractCertificationReport report =
                new VenueContractCertificationRunner(Clock.systemUTC()).certify(unavailable);

        System.out.println("OWNER_VENUE_CONTRACT_STATUS=" + report.status()
                + " REPORT_SHA256=" + report.reportSha256());
        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.NOT_VERIFIED);
        assertThat(report.eligibleForPromotion()).isFalse();
    }

    @Test
    void missingOkxDemoCredentialAndDriverRemainNotVerified() {
        VenueContractProfile profile = new VenueContractProfile(
                System.getenv().getOrDefault("VENUE_CONTRACT_OWNER", "NOT_CONFIGURED"),
                Exchange.OKX, VenueContractProfile.Environment.OKX_DEMO,
                "owner-demo-account", "PERPETUAL", VenueContractProfile.AccountMode.HEDGE,
                VenueContractProfile.MarginMode.CROSS, URI.create("https://www.okx.com"),
                URI.create("wss://wspap.okx.com/ws/v5/private"), true,
                EnumSet.of(VenueContractProfile.Permission.READ_ACCOUNT,
                        VenueContractProfile.Permission.READ_ORDERS,
                        VenueContractProfile.Permission.READ_FILLS,
                        VenueContractProfile.Permission.TRADE),
                true, "owner-network-egress-evidence-NOT_SUPPLIED");
        VenueContractProbe unavailable = new UnavailableVenueContractProbe(profile,
                "Owner-managed OKX demo credential and driver are NOT_VERIFIED");

        VenueContractCertificationReport report =
                new VenueContractCertificationRunner(Clock.systemUTC()).certify(unavailable);

        System.out.println("OWNER_OKX_CONTRACT_STATUS=" + report.status()
                + " REPORT_SHA256=" + report.reportSha256());
        assertThat(report.status()).isEqualTo(VenueContractCertificationReport.Status.NOT_VERIFIED);
        assertThat(report.eligibleForPromotion()).isFalse();
    }
}
