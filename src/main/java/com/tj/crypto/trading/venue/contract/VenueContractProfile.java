package com.tj.crypto.trading.venue.contract;

import com.tj.crypto.common.domain.Exchange;

import java.net.URI;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Non-secret declaration of the exact account and network boundary under certification.
 * Credentials, signed payloads and API keys deliberately have no field in this type.
 */
public record VenueContractProfile(
        String owner,
        Exchange exchange,
        Environment environment,
        String accountAlias,
        String product,
        AccountMode accountMode,
        MarginMode marginMode,
        URI restEndpoint,
        URI privateStreamEndpoint,
        boolean simulatedTradingHeader,
        Set<Permission> permissions,
        boolean withdrawalDisabled,
        String networkEgressAllowlistEvidence
) {
    private static final Set<String> BINANCE_REST_HOSTS = Set.of(
            "testnet.binance.vision", "testnet.binancefuture.com", "demo-fapi.binance.com");
    private static final Set<String> BINANCE_STREAM_HOSTS = Set.of(
            "stream.testnet.binance.vision", "fstream.binancefuture.com",
            "stream.binancefuture.com");
    private static final Set<String> OKX_REST_HOSTS = Set.of("www.okx.com");
    private static final Set<String> OKX_STREAM_HOSTS = Set.of("wspap.okx.com");
    private static final Set<Permission> REQUIRED_PERMISSIONS = EnumSet.of(
            Permission.READ_ACCOUNT, Permission.READ_ORDERS, Permission.READ_FILLS,
            Permission.TRADE);

    public VenueContractProfile {
        requireText(owner, "owner");
        requireText(accountAlias, "accountAlias");
        requireText(product, "product");
        owner = owner.trim();
        accountAlias = accountAlias.trim();
        product = product.trim();
        if (exchange == null || environment == null || accountMode == null || marginMode == null) {
            throw new IllegalArgumentException(
                    "exchange, environment, accountMode and marginMode are required");
        }
        if (restEndpoint == null || privateStreamEndpoint == null) {
            throw new IllegalArgumentException("REST and private stream endpoints are required");
        }
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        networkEgressAllowlistEvidence = networkEgressAllowlistEvidence == null
                ? "" : networkEgressAllowlistEvidence.trim();
    }

    /** Returns every static boundary defect; an empty list is required before any probe runs. */
    public List<String> boundaryViolations() {
        java.util.ArrayList<String> violations = new java.util.ArrayList<>();
        if (!permissions.containsAll(REQUIRED_PERMISSIONS)) {
            violations.add("Required read/trade permissions are not fully declared");
        }
        if (permissions.contains(Permission.WITHDRAW)) {
            violations.add("Withdrawal permission is prohibited");
        }
        if (!withdrawalDisabled) {
            violations.add("Withdrawal-disabled attestation is missing");
        }
        if (networkEgressAllowlistEvidence.isBlank()
                || "NOT_VERIFIED".equalsIgnoreCase(networkEgressAllowlistEvidence)) {
            violations.add("Network egress allowlist evidence is NOT_VERIFIED");
        }
        validateEndpoint(restEndpoint, "https", violations, "REST");
        validateEndpoint(privateStreamEndpoint, "wss", violations, "private stream");

        if (environment == Environment.OFFLINE_FAKE) {
            if (!isInvalidFixtureHost(restEndpoint) || !isInvalidFixtureHost(privateStreamEndpoint)) {
                violations.add("OFFLINE_FAKE endpoints must use the reserved .invalid domain");
            }
            return List.copyOf(violations);
        }
        if (environment == Environment.BINANCE_TESTNET) {
            if (exchange != Exchange.BINANCE) {
                violations.add("BINANCE_TESTNET requires exchange=BINANCE");
            }
            requireAllowedHost(restEndpoint, BINANCE_REST_HOSTS, "Binance testnet REST", violations);
            requireAllowedHost(privateStreamEndpoint, BINANCE_STREAM_HOSTS,
                    "Binance testnet private stream", violations);
        } else if (environment == Environment.OKX_DEMO) {
            if (exchange != Exchange.OKX) violations.add("OKX_DEMO requires exchange=OKX");
            requireAllowedHost(restEndpoint, OKX_REST_HOSTS, "OKX demo REST", violations);
            requireAllowedHost(privateStreamEndpoint, OKX_STREAM_HOSTS,
                    "OKX demo private stream", violations);
            if (!simulatedTradingHeader) {
                violations.add("OKX demo requires the simulated-trading header");
            }
        }
        return List.copyOf(violations);
    }

    private static void validateEndpoint(URI endpoint, String scheme,
                                         List<String> violations, String label) {
        if (!scheme.equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
            violations.add(label + " endpoint is not a credential-free "
                    + scheme.toUpperCase(Locale.ROOT) + " URI");
        }
    }

    private static void requireAllowedHost(URI endpoint, Set<String> hosts, String label,
                                           List<String> violations) {
        String host = endpoint.getHost();
        if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) {
            violations.add(label + " endpoint is outside the approved sandbox allowlist");
        }
    }

    private static boolean isInvalidFixtureHost(URI endpoint) {
        String host = endpoint.getHost();
        return host != null && ("invalid".equalsIgnoreCase(host)
                || host.toLowerCase(Locale.ROOT).endsWith(".invalid"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public boolean ownerControlledEnvironment() {
        return environment != Environment.OFFLINE_FAKE;
    }

    /** Stable, length-prefixed representation used to bind signed attestations to this profile. */
    public String canonicalForm() {
        String permissionNames = permissions.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .collect(Collectors.joining(","));
        StringBuilder value = new StringBuilder();
        appendCanonical(value, "owner", owner);
        appendCanonical(value, "exchange", exchange.name());
        appendCanonical(value, "environment", environment.name());
        appendCanonical(value, "accountAlias", accountAlias);
        appendCanonical(value, "product", product);
        appendCanonical(value, "accountMode", accountMode.name());
        appendCanonical(value, "marginMode", marginMode.name());
        appendCanonical(value, "restEndpoint", restEndpoint.normalize().toASCIIString());
        appendCanonical(value, "privateStreamEndpoint",
                privateStreamEndpoint.normalize().toASCIIString());
        appendCanonical(value, "simulatedTradingHeader",
                Boolean.toString(simulatedTradingHeader));
        appendCanonical(value, "permissions", permissionNames);
        appendCanonical(value, "withdrawalDisabled", Boolean.toString(withdrawalDisabled));
        appendCanonical(value, "networkEgressAllowlistEvidence",
                networkEgressAllowlistEvidence);
        return value.toString();
    }

    public String profileSha256() {
        return VenueContractEvidenceDigest.sha256(canonicalForm());
    }

    private static void appendCanonical(StringBuilder target, String name, String value) {
        target.append(name).append('=').append(value.length()).append(':').append(value).append(';');
    }

    public enum Environment { OFFLINE_FAKE, BINANCE_TESTNET, OKX_DEMO }
    public enum AccountMode { ONE_WAY, HEDGE }
    public enum MarginMode { CASH, CROSS, ISOLATED }
    public enum Permission { READ_ACCOUNT, READ_ORDERS, READ_FILLS, TRADE, WITHDRAW }
}
