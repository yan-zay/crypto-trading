package com.tj.crypto.trading.venue.stream;

import com.tj.crypto.common.domain.MarketType;

import java.util.List;

public interface PrivateVenueEventParser {
    List<VenuePrivateEvent> parse(String payload, MarketType marketType);
}
