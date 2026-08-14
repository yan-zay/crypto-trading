package com.tj.crypto.trading.venue.okx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.trading.venue.LiveTradingWriteGuard;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import com.tj.crypto.trading.venue.PrivateVenueGateway;
import com.tj.crypto.trading.venue.VenueAccountSnapshot;
import com.tj.crypto.trading.venue.VenueApiException;
import com.tj.crypto.trading.venue.VenueBalance;
import com.tj.crypto.trading.venue.VenueCancelCommand;
import com.tj.crypto.trading.venue.VenueHttpSupport;
import com.tj.crypto.trading.venue.VenueOrderCommand;
import com.tj.crypto.trading.venue.VenueOrderSnapshot;
import com.tj.crypto.trading.venue.VenueOrderState;
import com.tj.crypto.trading.venue.VenuePosition;
import com.tj.crypto.trading.venue.crypto.CanonicalQuery;
import com.tj.crypto.trading.venue.crypto.HmacSigner;
import com.tj.crypto.trading.reconciliation.truth.VenueTruthCapabilities;
import com.tj.crypto.trading.reconciliation.truth.VenueTruthCapability;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Signed OKX v5 private REST adapter for Spot and USDT perpetual swaps. */
@Component
public class OkxPrivateRestGateway extends VenueHttpSupport implements PrivateVenueGateway {
    private static final MediaType JSON = MediaType.get("application/json");
    private final PrivateTradingProperties properties;
    private final LiveTradingWriteGuard writeGuard;

    public OkxPrivateRestGateway(OkHttpClient httpClient, ObjectMapper objectMapper,
                                 PrivateTradingProperties properties,
                                 LiveTradingWriteGuard writeGuard) {
        super(httpClient, objectMapper);
        this.properties = properties;
        this.writeGuard = writeGuard;
    }

    @Override
    public Exchange exchange() {
        return Exchange.OKX;
    }

    @Override
    public boolean configured() {
        var config = properties.getOkx();
        return config.isEnabled() && present(config.getApiKey())
                && present(config.getSecretKey()) && present(config.getPassphrase());
    }

    @Override
    public VenueTruthCapabilities truthCapabilities() {
        return VenueTruthCapabilities.partial(
                EnumSet.of(VenueTruthCapability.BALANCES, VenueTruthCapability.POSITIONS),
                "Signed OKX balance and position endpoints are implemented",
                "Account-wide active-order/recent-fill pagination is not implemented or owner-verified");
    }

    @Override
    public VenueOrderSnapshot place(VenueOrderCommand command) {
        writeGuard.requireWriteEnabled(exchange());
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", instrumentId(command.instrument()));
        body.put("tdMode", tradingMode(command));
        body.put("side", command.tradeSide().name().toLowerCase());
        body.put("ordType", command.orderType().name().toLowerCase());
        body.put("sz", command.quantity().toPlainString());
        body.put("clOrdId", command.clientOrderId());
        if (command.price() != null) body.put("px", command.price().toPlainString());
        if (command.instrument().marketType() == MarketType.PERPETUAL) {
            body.put("reduceOnly", command.reduceOnly());
            if (properties.getOkx().isHedgeMode()) {
                body.put("posSide", command.positionSide().name().toLowerCase());
            }
        }
        JsonNode item = firstData(request("POST", "/api/v5/trade/order", Map.of(), body));
        ensureItemSuccess(item);
        return new VenueOrderSnapshot(text(item, "ordId"), text(item, "clOrdId"),
                "accepted", VenueOrderState.ACCEPTED, command.quantity(), BigDecimal.ZERO,
                null, System.currentTimeMillis(), false);
    }

    @Override
    public VenueOrderSnapshot cancel(VenueCancelCommand command) {
        writeGuard.requireCancelEnabled(exchange());
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", instrumentId(command.instrument()));
        if (present(command.venueOrderId())) body.put("ordId", command.venueOrderId());
        else body.put("clOrdId", command.clientOrderId());
        JsonNode item = firstData(request("POST", "/api/v5/trade/cancel-order", Map.of(), body));
        ensureItemSuccess(item);
        // OKX acknowledges the cancel request; the orders channel/query supplies the final state.
        return new VenueOrderSnapshot(text(item, "ordId"), text(item, "clOrdId"),
                "cancel_requested", VenueOrderState.CANCEL_PENDING, BigDecimal.ZERO,
                BigDecimal.ZERO, null, System.currentTimeMillis(), false);
    }

    @Override
    public VenueOrderSnapshot query(VenueCancelCommand command) {
        requireConfigured();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("instId", instrumentId(command.instrument()));
        if (present(command.venueOrderId())) query.put("ordId", command.venueOrderId());
        else query.put("clOrdId", command.clientOrderId());
        return parseOrder(firstData(request("GET", "/api/v5/trade/order", query, null)));
    }

    @Override
    public VenueAccountSnapshot account() {
        requireConfigured();
        List<VenueBalance> balances = new ArrayList<>();
        List<VenuePosition> positions = new ArrayList<>();
        JsonNode account = firstData(request("GET", "/api/v5/account/balance", Map.of(), null));
        for (JsonNode detail : account.path("details")) {
            BigDecimal total = decimal(detail, "cashBal");
            BigDecimal available = decimal(detail, "availBal");
            BigDecimal locked = decimal(detail, "frozenBal");
            if (total.signum() != 0 || available.signum() != 0 || locked.signum() != 0) {
                balances.add(new VenueBalance(text(detail, "ccy"), total, available, locked));
            }
        }
        JsonNode response = request("GET", "/api/v5/account/positions", Map.of(), null);
        for (JsonNode item : response.path("data")) {
            BigDecimal signedQuantity = decimal(item, "pos");
            if (signedQuantity.signum() == 0) continue;
            String rawSide = text(item, "posSide");
            String side = "short".equals(rawSide) || signedQuantity.signum() < 0 ? "SHORT" : "LONG";
            positions.add(new VenuePosition(text(item, "instId"), side, signedQuantity.abs(),
                    decimal(item, "avgPx"), decimal(item, "markPx"), decimal(item, "upl"),
                    intValue(item, "lever", 1), upper(text(item, "mgnMode"))));
        }
        return new VenueAccountSnapshot(exchange().name(), System.currentTimeMillis(), balances, positions);
    }

    private JsonNode request(String method, String path, Map<String, Object> query,
                             Map<String, Object> body) {
        String encodedQuery = CanonicalQuery.encode(query);
        String requestPath = path + (encodedQuery.isBlank() ? "" : "?" + encodedQuery);
        String bodyJson = body == null ? "" : json(body);
        String timestamp = Instant.now().toString();
        String signature = HmacSigner.sha256Base64(properties.getOkx().getSecretKey(),
                timestamp + method + requestPath + bodyJson);
        Request.Builder request = new Request.Builder()
                .url(properties.getOkx().getRestBaseUrl() + requestPath)
                .header("OK-ACCESS-KEY", properties.getOkx().getApiKey())
                .header("OK-ACCESS-SIGN", signature)
                .header("OK-ACCESS-TIMESTAMP", timestamp)
                .header("OK-ACCESS-PASSPHRASE", properties.getOkx().getPassphrase());
        if (properties.getOkx().isSimulatedTrading()) request.header("x-simulated-trading", "1");
        request.method(method, body == null ? null : RequestBody.create(bodyJson, JSON));
        JsonNode response = execute(request.build(), "OKX");
        if (!"0".equals(text(response, "code"))) {
            throw new VenueApiException("OKX private API rejected request", errorCode(response), 200);
        }
        return response;
    }

    private VenueOrderSnapshot parseOrder(JsonNode item) {
        String raw = text(item, "state");
        VenueOrderState state = mapStatus(raw);
        return new VenueOrderSnapshot(text(item, "ordId"), text(item, "clOrdId"), raw, state,
                decimal(item, "sz"), decimal(item, "accFillSz"), decimalOrNull(item, "avgPx"),
                longValue(item, "uTime", System.currentTimeMillis()), isFinal(state));
    }

    public static VenueOrderState mapStatus(String status) {
        if (status == null) return VenueOrderState.UNKNOWN;
        return switch (status) {
            case "live" -> VenueOrderState.ACCEPTED;
            case "partially_filled" -> VenueOrderState.PARTIALLY_FILLED;
            case "filled" -> VenueOrderState.FILLED;
            case "canceled", "mmp_canceled" -> VenueOrderState.CANCELLED;
            default -> VenueOrderState.UNKNOWN;
        };
    }

    private boolean isFinal(VenueOrderState state) {
        return state == VenueOrderState.FILLED || state == VenueOrderState.CANCELLED
                || state == VenueOrderState.REJECTED || state == VenueOrderState.EXPIRED;
    }

    private String instrumentId(Instrument instrument) {
        String base = instrument.baseAsset();
        String quote = instrument.quoteAsset();
        if (!present(base) || !present(quote)) {
            throw new IllegalArgumentException("OKX instrument requires base and quote assets");
        }
        return base + "-" + quote + (instrument.marketType() == MarketType.PERPETUAL ? "-SWAP" : "");
    }

    private String tradingMode(VenueOrderCommand command) {
        if (command.instrument().marketType() == MarketType.SPOT) return "cash";
        return "CROSS".equals(command.marginMode()) ? "cross" : "isolated";
    }

    private JsonNode firstData(JsonNode response) {
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new VenueApiException("OKX private API returned no data", "EMPTY_DATA", 200);
        }
        return data.get(0);
    }

    private void ensureItemSuccess(JsonNode item) {
        String code = text(item, "sCode");
        if (code != null && !"0".equals(code)) {
            throw new VenueApiException("OKX trade request rejected", code, 200);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("OKX request is not serializable", e);
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        BigDecimal value = decimalOrNull(node, field);
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private int intValue(JsonNode node, String field, int fallback) {
        String value = text(node, field);
        if (value == null || value.isBlank()) return fallback;
        try {
            return new BigDecimal(value).intValue();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long longValue(JsonNode node, String field, long fallback) {
        String value = text(node, field);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }

    private void requireConfigured() {
        if (!configured()) throw new IllegalStateException("OKX private API is not configured");
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
