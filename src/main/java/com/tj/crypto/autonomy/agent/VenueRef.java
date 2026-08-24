package com.tj.crypto.autonomy.agent;

/** 交易场所的中性引用；不依赖任何私有 venue adapter 类型。 */
public record VenueRef(String venueId) {
    public VenueRef {
        venueId = AutonomyValues.text(venueId, "venueId");
    }
}
