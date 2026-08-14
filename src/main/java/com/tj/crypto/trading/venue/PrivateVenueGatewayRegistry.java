package com.tj.crypto.trading.venue;

import com.tj.crypto.common.domain.Exchange;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PrivateVenueGatewayRegistry {
    private final Map<Exchange, PrivateVenueGateway> gateways = new EnumMap<>(Exchange.class);

    public PrivateVenueGatewayRegistry(List<PrivateVenueGateway> discovered) {
        for (PrivateVenueGateway gateway : discovered) {
            if (gateways.put(gateway.exchange(), gateway) != null) {
                throw new IllegalStateException("Duplicate private gateway: " + gateway.exchange());
            }
        }
    }

    public PrivateVenueGateway require(Exchange exchange) {
        PrivateVenueGateway gateway = gateways.get(exchange);
        if (gateway == null) throw new IllegalArgumentException("No private gateway for " + exchange);
        return gateway;
    }

    public List<PrivateVenueGateway> all() {
        return List.copyOf(gateways.values());
    }
}
