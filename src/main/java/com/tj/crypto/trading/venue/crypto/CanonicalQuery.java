package com.tj.crypto.trading.venue.crypto;

import okhttp3.HttpUrl;

import java.util.Map;

public final class CanonicalQuery {
    private CanonicalQuery() {}

    public static String encode(Map<String, ?> values) {
        HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme("https").host("canonical.invalid");
        values.forEach((key, value) -> {
            if (value != null) builder.addQueryParameter(key, String.valueOf(value));
        });
        String query = builder.build().encodedQuery();
        return query == null ? "" : query;
    }
}
