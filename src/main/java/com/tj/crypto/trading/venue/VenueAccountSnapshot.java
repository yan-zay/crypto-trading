package com.tj.crypto.trading.venue;

import java.util.List;

public record VenueAccountSnapshot(String exchange, long eventTimeMs,
                                   List<VenueBalance> balances,
                                   List<VenuePosition> positions) {}
