package com.tj.crypto.trading.venue.crypto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {
    @Test
    void signsKnownSha256Vector() {
        assertThat(HmacSigner.sha256Hex("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void canonicalQueryPreservesOrderAndPercentEncodes() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("symbol", "BTC USDT");
        values.put("quantity", "0.10");
        assertThat(CanonicalQuery.encode(values)).isEqualTo("symbol=BTC%20USDT&quantity=0.10");
    }
}
