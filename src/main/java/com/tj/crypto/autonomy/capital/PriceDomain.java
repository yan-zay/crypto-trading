package com.tj.crypto.autonomy.capital;

/** Economic price domain; some asset classes can validly trade at negative prices. */
public enum PriceDomain {
    POSITIVE_ONLY,
    SIGNED_ALLOWED
}
