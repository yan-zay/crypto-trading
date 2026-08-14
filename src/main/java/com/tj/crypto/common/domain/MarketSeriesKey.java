package com.tj.crypto.common.domain;

import java.util.Objects;

/** Instrument 与时间周期共同组成的行情序列主键。 */
public record MarketSeriesKey(InstrumentId instrumentId, Timeframe timeframe) {
    public MarketSeriesKey {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(timeframe, "timeframe");
    }

    public static MarketSeriesKey of(Instrument instrument, Timeframe timeframe) {
        return new MarketSeriesKey(instrument.id(), timeframe);
    }
}
