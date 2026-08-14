package com.tj.crypto.trading.venue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/** Shared HTTP mechanics; exchange protocol and signing remain in each gateway. */
public abstract class VenueHttpSupport {
    protected final OkHttpClient httpClient;
    protected final ObjectMapper objectMapper;

    protected VenueHttpSupport(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    protected JsonNode execute(Request request, String venue) {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            JsonNode json = body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            if (!response.isSuccessful()) {
                throw new VenueApiException(venue + " private API request failed",
                        errorCode(json), response.code());
            }
            return json;
        } catch (VenueApiException e) {
            throw e;
        } catch (IOException e) {
            throw new VenueApiException(venue + " private API transport failed", "TRANSPORT", 0);
        }
    }

    protected String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    protected String errorCode(JsonNode json) {
        String code = text(json, "code");
        if (code == null) code = text(json, "sCode");
        return code == null ? "HTTP_ERROR" : code;
    }
}
