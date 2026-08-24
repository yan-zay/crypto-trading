package com.tj.crypto.autonomy.capital;

/** Stable identifier for a licensed broker, exchange or other execution venue. */
public record Venue(String id) implements Comparable<Venue> {
    public Venue {
        id = CapitalValues.id(id, "venueId", 128);
    }

    @Override
    public int compareTo(Venue other) {
        return id.compareTo(other.id);
    }
}
