package com.tj.crypto.trading.venue.stream;

import com.tj.crypto.common.domain.Exchange;

public sealed interface VenuePrivateEvent permits VenueOrderUpdate, VenueAccountRefresh {
    Exchange exchange();
    String externalEventId();
    long eventTimeMs();
}
