package com.tj.crypto.trading.venue;

import java.math.BigDecimal;

public record VenueBalance(String asset, BigDecimal total, BigDecimal available, BigDecimal locked) {}
