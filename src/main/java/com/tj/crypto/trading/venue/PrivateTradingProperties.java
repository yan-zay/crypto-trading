package com.tj.crypto.trading.venue;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Credentials and independent safety gates for private venue connectivity. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.private-trading")
public class PrivateTradingProperties {
    private static final Set<String> BINANCE_SPOT_SANDBOX_HOSTS = Set.of(
            "testnet.binance.vision");
    private static final Set<String> BINANCE_PERPETUAL_SANDBOX_HOSTS = Set.of(
            "testnet.binancefuture.com", "demo-fapi.binance.com");
    private static final Set<String> BINANCE_SPOT_SANDBOX_WS_HOSTS = Set.of(
            "stream.testnet.binance.vision");
    private static final Set<String> BINANCE_PERPETUAL_SANDBOX_WS_HOSTS = Set.of(
            "fstream.binancefuture.com", "stream.binancefuture.com");
    private static final Set<String> OKX_SANDBOX_WS_HOSTS = Set.of("wspap.okx.com");
    private OperatingMode operatingMode = OperatingMode.RESEARCH_ONLY;
    private String targetJurisdiction = "";
    private String legalApprovalReference = "";
    private boolean liveWriteEnabled;
    private boolean userStreamsEnabled;
    private long receiveWindowMs = 5_000;
    private int reconciliationBatchSize = 200;
    private long writerLeaseTtlMs = 15_000;
    private long writerLeaseRenewIntervalMs = 5_000;
    private final Binance binance = new Binance();
    private final Okx okx = new Okx();

    public enum OperatingMode {
        /** Research, backtest and paper trading only. No live venue writes. */
        RESEARCH_ONLY,
        /** Venue demo/testnet writes only; production credentials are prohibited operationally. */
        SANDBOX,
        /** Explicitly approved, tightly bounded live canary. */
        LIVE_CANARY
    }

    @PostConstruct
    void validateStartupPolicy() {
        if (writerLeaseRenewIntervalMs <= 0 || writerLeaseTtlMs < writerLeaseRenewIntervalMs * 2) {
            throw new IllegalStateException("Writer lease TTL must be at least twice its positive renew interval");
        }
        boolean anyWriteFlag = liveWriteEnabled || binance.writeEnabled || okx.writeEnabled;
        if (operatingMode == OperatingMode.RESEARCH_ONLY && anyWriteFlag) {
            throw new IllegalStateException("RESEARCH_ONLY forbids every live venue write flag");
        }
        if (operatingMode == OperatingMode.SANDBOX) {
            if (binance.enabled && !binance.testnet) {
                throw new IllegalStateException("SANDBOX Binance writes require binance.testnet=true");
            }
            if (binance.enabled) {
                requireSandboxHttpsHost(binance.spotRestBaseUrl, BINANCE_SPOT_SANDBOX_HOSTS,
                        "Binance Spot REST");
                requireSandboxHttpsHost(binance.perpetualRestBaseUrl,
                        BINANCE_PERPETUAL_SANDBOX_HOSTS, "Binance perpetual REST");
            }
            if (userStreamsEnabled && binance.enabled) {
                requireSandboxWebsocketHost(binance.spotUserStreamUrl,
                        BINANCE_SPOT_SANDBOX_WS_HOSTS, "Binance Spot private WebSocket");
                requireSandboxWebsocketHost(binance.perpetualUserStreamUrl,
                        BINANCE_PERPETUAL_SANDBOX_WS_HOSTS,
                        "Binance perpetual private WebSocket");
            }
            if (okx.enabled && !okx.simulatedTrading) {
                throw new IllegalStateException("SANDBOX OKX writes require okx.simulated-trading=true");
            }
            if (userStreamsEnabled && okx.enabled) {
                requireSandboxWebsocketHost(okx.privateWebsocketUrl,
                        OKX_SANDBOX_WS_HOSTS, "OKX private WebSocket");
            }
        }
        if (operatingMode == OperatingMode.LIVE_CANARY && anyWriteFlag
                && (targetJurisdiction == null || targetJurisdiction.isBlank()
                || legalApprovalReference == null || legalApprovalReference.isBlank())) {
            throw new IllegalStateException(
                    "LIVE_CANARY writes require target-jurisdiction and legal-approval-reference");
        }
    }

    private void requireSandboxHttpsHost(String rawUrl, Set<String> allowedHosts, String endpoint) {
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            boolean safe = "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getPort() == -1
                    && host != null
                    && allowedHosts.contains(host.toLowerCase(Locale.ROOT))
                    && (uri.getRawUserInfo() == null || uri.getRawUserInfo().isBlank())
                    && (uri.getRawPath() == null || uri.getRawPath().isBlank()
                    || "/".equals(uri.getRawPath()));
            if (!safe) throw new IllegalArgumentException("unsafe endpoint");
        } catch (RuntimeException e) {
            throw new IllegalStateException(endpoint
                    + " must use an approved HTTPS sandbox origin in SANDBOX mode", e);
        }
    }

    private void requireSandboxWebsocketHost(String rawUrl, Set<String> allowedHosts,
                                             String endpoint) {
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            boolean safe = "wss".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && allowedHosts.contains(host.toLowerCase(Locale.ROOT))
                    && (uri.getRawUserInfo() == null || uri.getRawUserInfo().isBlank());
            if (!safe) throw new IllegalArgumentException("unsafe endpoint");
        } catch (RuntimeException e) {
            throw new IllegalStateException(endpoint
                    + " must use an approved WSS sandbox origin in SANDBOX mode", e);
        }
    }

    @Getter
    @Setter
    public static class Binance {
        private boolean enabled;
        private boolean writeEnabled;
        private String apiKey = "";
        private String secretKey = "";
        private String spotRestBaseUrl = "https://api.binance.com";
        private String perpetualRestBaseUrl = "https://fapi.binance.com";
        private String spotUserStreamUrl = "wss://stream.binance.com:9443/ws";
        private String perpetualUserStreamUrl = "wss://fstream.binance.com/ws";
        private boolean testnet;
        private boolean hedgeMode;
    }

    @Getter
    @Setter
    public static class Okx {
        private boolean enabled;
        private boolean writeEnabled;
        private String apiKey = "";
        private String secretKey = "";
        private String passphrase = "";
        private String restBaseUrl = "https://www.okx.com";
        private String privateWebsocketUrl = "wss://ws.okx.com:8443/ws/v5/private";
        private boolean simulatedTrading;
        private boolean hedgeMode;
    }
}
