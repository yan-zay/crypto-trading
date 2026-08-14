package com.tj.crypto.trading.venue.binance;

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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Signed Binance Spot and USD-M Futures private REST adapter. */
@Component
public class BinancePrivateRestGateway extends VenueHttpSupport implements PrivateVenueGateway {
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    private final PrivateTradingProperties properties;
    private final LiveTradingWriteGuard writeGuard;

    public BinancePrivateRestGateway(OkHttpClient httpClient, ObjectMapper objectMapper,
                                     PrivateTradingProperties properties,
                                     LiveTradingWriteGuard writeGuard) {
        super(httpClient, objectMapper);
        this.properties = properties;
        this.writeGuard = writeGuard;
    }

    @Override
    public Exchange exchange() {
        return Exchange.BINANCE;
    }

    @Override
    public boolean configured() {
        var config = properties.getBinance();
        return config.isEnabled() && present(config.getApiKey()) && present(config.getSecretKey());
    }

    @Override
    public VenueTruthCapabilities truthCapabilities() {
        return VenueTruthCapabilities.partial(
                EnumSet.of(VenueTruthCapability.BALANCES, VenueTruthCapability.POSITIONS),
                "Signed Binance account endpoints are implemented",
                "Account-wide active-order/recent-fill pagination is not implemented or owner-verified");
    }

    @Override
    public VenueOrderSnapshot place(VenueOrderCommand command) {
        writeGuard.requireWriteEnabled(exchange());
        requireConfigured();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", command.instrument().symbol());
        parameters.put("side", command.tradeSide().name());
        parameters.put("type", command.orderType().name());
        parameters.put("quantity", command.quantity().toPlainString());
        parameters.put("newClientOrderId", command.clientOrderId());
        if (command.orderType() == com.tj.crypto.execution.model.OrderType.LIMIT) {
            parameters.put("timeInForce", "GTC");
            parameters.put("price", command.price().toPlainString());
        }
        if (command.instrument().marketType() == MarketType.PERPETUAL) {
            if (properties.getBinance().isHedgeMode()) {
                parameters.put("positionSide", command.positionSide().name());
            } else if (command.reduceOnly()) {
                parameters.put("reduceOnly", "true");
            }
        }
        JsonNode json = signed("POST", path(command.instrument(), "/api/v3/order", "/fapi/v1/order"),
                command.instrument(), parameters);
        return parseOrder(json);
    }

    @Override
    public VenueOrderSnapshot cancel(VenueCancelCommand command) {
        writeGuard.requireCancelEnabled(exchange());
        requireConfigured();
        Map<String, Object> parameters = orderReference(command);
        JsonNode json = signed("DELETE", path(command.instrument(), "/api/v3/order", "/fapi/v1/order"),
                command.instrument(), parameters);
        return parseOrder(json);
    }

    @Override
    public VenueOrderSnapshot query(VenueCancelCommand command) {
        requireConfigured();
        JsonNode json = signed("GET", path(command.instrument(), "/api/v3/order", "/fapi/v1/order"),
                command.instrument(), orderReference(command));
        return parseOrder(json);
    }

    @Override
    public VenueAccountSnapshot account() {
        requireConfigured();
        long now = System.currentTimeMillis();
        List<VenueBalance> balances = new ArrayList<>();
        List<VenuePosition> positions = new ArrayList<>();
        readSpotAccount(balances);
        readFuturesAccount(balances, positions);
        return new VenueAccountSnapshot(exchange().name(), now, balances, positions);
    }

    @Override
    public VenueAccountSnapshot account(Instrument instrument) {
        requireConfigured();
        List<VenueBalance> balances = new ArrayList<>();
        List<VenuePosition> positions = new ArrayList<>();
        if (instrument.marketType() == MarketType.SPOT) readSpotAccount(balances);
        else readFuturesAccount(balances, positions);
        return new VenueAccountSnapshot(exchange().name(), System.currentTimeMillis(), balances, positions);
    }

    private void readSpotAccount(List<VenueBalance> balances) {
        JsonNode spot = signed("GET", "/api/v3/account",
                Instrument.of(Exchange.BINANCE, MarketType.SPOT, "BTCUSDT"), Map.of());
        for (JsonNode item : spot.path("balances")) {
            BigDecimal available = decimal(item, "free");
            BigDecimal locked = decimal(item, "locked");
            if (available.signum() != 0 || locked.signum() != 0) {
                balances.add(new VenueBalance(text(item, "asset"), available.add(locked), available, locked));
            }
        }
    }

    private void readFuturesAccount(List<VenueBalance> balances, List<VenuePosition> positions) {
        JsonNode futures = signed("GET", "/fapi/v3/account",
                Instrument.of(Exchange.BINANCE, MarketType.PERPETUAL, "BTCUSDT"), Map.of());
        for (JsonNode item : futures.path("assets")) {
            BigDecimal total = decimal(item, "walletBalance");
            BigDecimal available = decimal(item, "availableBalance");
            if (total.signum() != 0 || available.signum() != 0) {
                balances.add(new VenueBalance(text(item, "asset"), total, available,
                        total.subtract(available).max(BigDecimal.ZERO)));
            }
        }
        for (JsonNode item : futures.path("positions")) {
            BigDecimal quantity = decimal(item, "positionAmt");
            if (quantity.signum() != 0) {
                positions.add(new VenuePosition(text(item, "symbol"),
                        quantity.signum() > 0 ? "LONG" : "SHORT", quantity.abs(),
                        decimal(item, "entryPrice"), BigDecimal.ZERO,
                        decimal(item, "unrealizedProfit"), item.path("leverage").asInt(1),
                        text(item, "isolated") != null && item.path("isolated").asBoolean()
                                ? "ISOLATED" : "CROSS"));
            }
        }
    }

    private JsonNode signed(String method, String path, Instrument instrument,
                            Map<String, Object> source) {
        Map<String, Object> parameters = new LinkedHashMap<>(source);
        parameters.put("recvWindow", properties.getReceiveWindowMs());
        parameters.put("timestamp", System.currentTimeMillis());
        String unsigned = CanonicalQuery.encode(parameters);
        String signature = HmacSigner.sha256Hex(properties.getBinance().getSecretKey(), unsigned);
        String payload = unsigned + "&signature=" + signature;
        String baseUrl = instrument.marketType() == MarketType.SPOT
                ? properties.getBinance().getSpotRestBaseUrl()
                : properties.getBinance().getPerpetualRestBaseUrl();
        Request.Builder request = new Request.Builder()
                .header("X-MBX-APIKEY", properties.getBinance().getApiKey());
        if ("POST".equals(method)) {
            request.url(baseUrl + path).post(RequestBody.create(payload, FORM));
        } else {
            request.url(baseUrl + path + "?" + payload).method(method, null);
        }
        return execute(request.build(), "BINANCE");
    }

    private Map<String, Object> orderReference(VenueCancelCommand command) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", command.instrument().symbol());
        if (present(command.venueOrderId())) parameters.put("orderId", command.venueOrderId());
        else parameters.put("origClientOrderId", command.clientOrderId());
        return parameters;
    }

    private VenueOrderSnapshot parseOrder(JsonNode json) {
        String raw = text(json, "status");
        VenueOrderState state = raw == null && text(json, "orderId") != null
                ? VenueOrderState.ACCEPTED : mapStatus(raw);
        return new VenueOrderSnapshot(text(json, "orderId"), text(json, "clientOrderId"), raw, state,
                decimal(json, "origQty"), decimal(json, "executedQty"), decimal(json, "avgPrice"),
                json.path("updateTime").asLong(json.path("transactTime").asLong(System.currentTimeMillis())),
                isFinal(state));
    }

    public static VenueOrderState mapStatus(String status) {
        if (status == null) return VenueOrderState.UNKNOWN;
        return switch (status) {
            case "NEW" -> VenueOrderState.ACCEPTED;
            case "PARTIALLY_FILLED" -> VenueOrderState.PARTIALLY_FILLED;
            case "FILLED" -> VenueOrderState.FILLED;
            case "PENDING_CANCEL" -> VenueOrderState.CANCEL_PENDING;
            case "CANCELED" -> VenueOrderState.CANCELLED;
            case "REJECTED" -> VenueOrderState.REJECTED;
            case "EXPIRED", "EXPIRED_IN_MATCH" -> VenueOrderState.EXPIRED;
            default -> VenueOrderState.UNKNOWN;
        };
    }

    private boolean isFinal(VenueOrderState state) {
        return List.of(VenueOrderState.FILLED, VenueOrderState.CANCELLED,
                VenueOrderState.REJECTED, VenueOrderState.EXPIRED).contains(state);
    }

    private String path(Instrument instrument, String spot, String perpetual) {
        return instrument.marketType() == MarketType.SPOT ? spot : perpetual;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private void requireConfigured() {
        if (!configured()) throw new IllegalStateException("Binance private API is not configured");
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
