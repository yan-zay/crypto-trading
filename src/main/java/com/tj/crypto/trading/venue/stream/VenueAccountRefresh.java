package com.tj.crypto.trading.venue.stream;

import com.tj.crypto.common.domain.Exchange;

/** Account/position pushes trigger a fresh signed account snapshot and reconciliation. */
public record VenueAccountRefresh(Exchange exchange, String externalEventId,
                                  long eventTimeMs, String channel) implements VenuePrivateEvent {}
