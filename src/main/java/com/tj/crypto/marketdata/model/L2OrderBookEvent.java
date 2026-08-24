package com.tj.crypto.marketdata.model;

import com.tj.crypto.common.domain.Instrument;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 标准化 L2 订单簿快照或增量。
 * bids 必须按价格降序、asks 必须按价格升序，确保归档与重放的确定性。
 */
public record L2OrderBookEvent(
        Instrument instrument,
        EventMetadata metadata,
        String bookEpoch,
        long firstUpdateId,
        long lastUpdateId,
        Long previousUpdateId,
        Long checksum,
        boolean snapshot,
        List<OrderBookLevel> bids,
        List<OrderBookLevel> asks
) implements MarketEvent {
    public L2OrderBookEvent {
        MarketEventValidation.requireIdentity(instrument, metadata);
        if (bookEpoch == null || bookEpoch.isBlank()) {
            throw new IllegalArgumentException("bookEpoch must not be blank");
        }
        bookEpoch = bookEpoch.trim();
        if (firstUpdateId < 0 || lastUpdateId < firstUpdateId) {
            throw new IllegalArgumentException("update id range is invalid");
        }
        if (previousUpdateId != null && previousUpdateId < 0) {
            throw new IllegalArgumentException("previousUpdateId must not be negative");
        }
        bids = immutableLevels(bids, true, "bids");
        asks = immutableLevels(asks, false, "asks");
        if (bids.isEmpty() && asks.isEmpty()) {
            throw new IllegalArgumentException("at least one bid or ask level is required");
        }
        if (snapshot && !bids.isEmpty() && !asks.isEmpty()
                && bids.get(0).price().compareTo(asks.get(0).price()) >= 0) {
            throw new IllegalArgumentException("snapshot order book must not be crossed");
        }
        if (snapshot && (containsDeletion(bids) || containsDeletion(asks))) {
            throw new IllegalArgumentException("snapshot levels must have positive quantity");
        }
    }

    private static List<OrderBookLevel> immutableLevels(List<OrderBookLevel> levels,
                                                         boolean descending,
                                                         String field) {
        if (levels == null) throw new IllegalArgumentException(field + " is required");
        List<OrderBookLevel> copy = List.copyOf(levels);
        Set<BigDecimal> prices = new HashSet<>();
        BigDecimal previous = null;
        for (OrderBookLevel level : copy) {
            if (!prices.add(level.price().stripTrailingZeros())) {
                throw new IllegalArgumentException(field + " contains duplicate prices");
            }
            if (previous != null) {
                int comparison = previous.compareTo(level.price());
                if ((descending && comparison <= 0) || (!descending && comparison >= 0)) {
                    throw new IllegalArgumentException(field + " has non-canonical price ordering");
                }
            }
            previous = level.price();
        }
        return copy;
    }

    private static boolean containsDeletion(List<OrderBookLevel> levels) {
        return levels.stream().anyMatch(level -> level.quantity().signum() == 0);
    }
}
