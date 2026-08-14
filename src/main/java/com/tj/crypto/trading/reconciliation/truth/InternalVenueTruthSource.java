package com.tj.crypto.trading.reconciliation.truth;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.trading.venue.VenueAccountSnapshot;

import java.util.List;

/** Durable internal projection to compare with an account-wide venue snapshot. */
public interface InternalVenueTruthSource {
    VenueTruthCapabilities capabilities(Exchange exchange);

    default List<VenueTruthOrder> activeOrders(Exchange exchange, VenueTruthRequest request) {
        throw new UnsupportedOperationException("Internal active-order truth projection is unsupported");
    }

    default List<VenueTruthFill> recentFills(Exchange exchange, VenueTruthRequest request) {
        throw new UnsupportedOperationException("Internal recent-fill truth projection is unsupported");
    }

    default VenueAccountSnapshot account(Exchange exchange) {
        throw new UnsupportedOperationException("Internal balance/position truth projection is unsupported");
    }
}
