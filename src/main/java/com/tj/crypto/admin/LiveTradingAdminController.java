package com.tj.crypto.admin;

import com.tj.crypto.admin.application.AuditService;
import com.tj.crypto.admin.domain.UserDO;
import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.trading.venue.LiveOrderRequest;
import com.tj.crypto.trading.venue.LiveOrderService;
import com.tj.crypto.trading.venue.PrivateTradingProperties;
import com.tj.crypto.trading.venue.PrivateVenueGateway;
import com.tj.crypto.trading.venue.PrivateVenueGatewayRegistry;
import com.tj.crypto.trading.venue.stream.PrivateStreamStatus;
import com.tj.crypto.trading.venue.stream.PrivateStreamStatusProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read/private stream diagnostics and explicitly gated live trade commands. */
@RestController
@RequestMapping("/api/admin/live-trading")
@RequiredArgsConstructor
public class LiveTradingAdminController {
    private final PrivateTradingProperties properties;
    private final PrivateVenueGatewayRegistry gatewayRegistry;
    private final List<PrivateStreamStatusProvider> streamStatusProviders;
    private final LiveOrderService liveOrderService;
    private final AuditService auditService;

    @GetMapping("/venues")
    public Map<String, Object> venues() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("globalWriteEnabled", properties.isLiveWriteEnabled());
        result.put("userStreamsEnabled", properties.isUserStreamsEnabled());
        result.put("venues", gatewayRegistry.all().stream().map(gateway -> Map.of(
                "exchange", gateway.exchange().name(),
                "configured", gateway.configured(),
                "writeEnabled", venueWriteEnabled(gateway.exchange()))).toList());
        result.put("streams", streamStatusProviders.stream()
                .flatMap(provider -> provider.statuses().stream()).toList());
        return result;
    }

    @GetMapping("/accounts/{exchange}")
    public Object account(@PathVariable Exchange exchange) {
        return gateway(exchange).account();
    }

    @PostMapping("/orders")
    public Object place(@RequestBody LiveOrderRequest source, HttpServletRequest request) {
        LiveOrderRequest command = new LiveOrderRequest(source.accountId(), source.clientOrderId(),
                source.strategyId(), source.exchange(), source.marketType(), source.symbol(), source.side(),
                source.positionSide(), source.orderType(), source.quantity(), source.limitPrice(),
                source.referencePrice(), source.leverage(), source.reduceOnly(), source.marginMode(),
                source.correlationId() == null ? correlationId(request) : source.correlationId());
        Object result = liveOrderService.place(command);
        auditService.logOperation(operator(request), "PLACE_LIVE_ORDER",
                "exchange=" + source.exchange() + ",clientOrderId=" + command.clientOrderId());
        return result;
    }

    @PostMapping("/orders/{orderId}/cancel")
    public Object cancel(@PathVariable String orderId, HttpServletRequest request) {
        Object result = liveOrderService.cancel(orderId);
        auditService.logOperation(operator(request), "CANCEL_LIVE_ORDER", "orderId=" + orderId);
        return result;
    }

    @PostMapping("/orders/{orderId}/reconcile")
    public Object reconcile(@PathVariable String orderId, HttpServletRequest request) {
        Object result = liveOrderService.reconcile(orderId);
        auditService.logOperation(operator(request), "RECONCILE_LIVE_ORDER", "orderId=" + orderId);
        return result;
    }

    private PrivateVenueGateway gateway(Exchange exchange) {
        if (exchange == Exchange.COINGLASS) throw new IllegalArgumentException("Coinglass is not an execution venue");
        return gatewayRegistry.require(exchange);
    }

    private boolean venueWriteEnabled(Exchange exchange) {
        return switch (exchange) {
            case BINANCE -> properties.getBinance().isWriteEnabled();
            case OKX -> properties.getOkx().isWriteEnabled();
            case COINGLASS -> false;
        };
    }

    private String correlationId(HttpServletRequest request) {
        String value = request.getHeader("X-Correlation-Id");
        return value == null || value.isBlank() ? java.util.UUID.randomUUID().toString() : value;
    }

    private String operator(HttpServletRequest request) {
        Object value = request.getAttribute("currentUser");
        return value instanceof UserDO user ? user.getUsername() : "system";
    }
}
