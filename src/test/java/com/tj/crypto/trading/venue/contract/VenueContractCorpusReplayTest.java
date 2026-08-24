package com.tj.crypto.trading.venue.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VenueContractCorpusReplayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rateLimitCorpusIsContentAddressedAndProvesBoundedRetryTiming() throws IOException {
        byte[] bytes = resource("venue-contract/rate-limit-corpus.json");
        JsonNode root = mapper.readTree(bytes);
        JsonNode requests = root.path("requests");

        assertThat(VenueContractEvidenceDigest.sha256(bytes))
                .isEqualTo("4178bb73573bac756878bd02f8082dc8f83597a704a95764faebf4f99578f19a");
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.path("corpusId").asText()).isEqualTo("binance-testnet-rate-limit-v1");
        assertThat(requests).hasSize(4);

        JsonNode limited = requests.get(2);
        JsonNode resumed = requests.get(3);
        assertThat(limited.path("httpStatus").asInt()).isEqualTo(429);
        assertThat(limited.path("venueCode").asText()).isEqualTo("-1003");
        assertThat(resumed.path("atMs").asLong() - limited.path("atMs").asLong())
                .isGreaterThanOrEqualTo(limited.path("retryAfterMs").asLong());
        assertThat(root.path("expected").path("maxRetryCount").asInt()).isEqualTo(1);
    }

    @Test
    void disconnectCorpusRetainsDuplicateDelayReconnectAndBackfillFacts() throws IOException {
        byte[] bytes = resource("venue-contract/private-stream-disconnect-corpus.json");
        JsonNode root = mapper.readTree(bytes);
        JsonNode frames = root.path("frames");

        assertThat(VenueContractEvidenceDigest.sha256(bytes))
                .isEqualTo("f7ed752fed4a8c098161698b6d52fbf02e1191851391db045152833e5f79d8b8");
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(frames).hasSize(7);

        Set<String> kinds = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        boolean duplicate = false;
        boolean delayed = false;
        for (JsonNode frame : frames) {
            kinds.add(frame.path("kind").asText());
            if (!eventIds.add(frame.path("eventId").asText())) duplicate = true;
            if (frame.path("arrivalTimeMs").asLong() - frame.path("eventTimeMs").asLong()
                    >= 1_000) delayed = true;
        }
        assertThat(kinds).contains("DISCONNECT", "RECONNECT", "BACKFILL");
        assertThat(duplicate).isTrue();
        assertThat(delayed).isTrue();
        assertThat(root.path("expected").properties())
                .allMatch(entry -> entry.getValue().asBoolean());
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing test corpus: " + name);
            return input.readAllBytes();
        }
    }
}
