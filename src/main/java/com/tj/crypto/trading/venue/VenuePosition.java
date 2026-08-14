package com.tj.crypto.trading.venue;

import java.math.BigDecimal;

public record VenuePosition(String symbol, String side, BigDecimal quantity,
                            BigDecimal entryPrice, BigDecimal markPrice,
                            BigDecimal unrealizedPnl, int leverage, String marginMode) {}
