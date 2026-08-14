package com.tj.crypto.trading.reconciliation.truth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.trading.venue.LiveTradingWriteGuard;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import com.tj.crypto.trading.venue.binance.BinancePrivateRestGateway;
import com.tj.crypto.trading.venue.okx.OkxPrivateRestGateway;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionVenueTruthCapabilitiesTest {
    @Test
    void adaptersExplicitlyKeepUnimplementedAccountWideFactsUnsupported() {
        PrivateTradingProperties properties = new PrivateTradingProperties();
        LiveTradingWriteGuard writeGuard = new LiveTradingWriteGuard(properties);
        OkHttpClient client = new OkHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        for (VenueTruthCapabilities capabilities : java.util.List.of(
                new BinancePrivateRestGateway(client, mapper, properties, writeGuard).truthCapabilities(),
                new OkxPrivateRestGateway(client, mapper, properties, writeGuard).truthCapabilities())) {
            assertThat(capabilities.supports(VenueTruthCapability.ACTIVE_ORDERS)).isFalse();
            assertThat(capabilities.supports(VenueTruthCapability.RECENT_FILLS)).isFalse();
            assertThat(capabilities.supports(VenueTruthCapability.BALANCES)).isTrue();
            assertThat(capabilities.supports(VenueTruthCapability.POSITIONS)).isTrue();
        }
    }
}
