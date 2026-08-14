package com.tj.crypto.admin;

import com.tj.crypto.marketdata.backfill.HistoricalDataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AdminExceptionHandlerTest {

    @Test
    void mapsHistoricalProviderFailureToBadGateway() {
        AdminExceptionHandler handler = new AdminExceptionHandler();

        var response = handler.upstreamDataFailure(
                new HistoricalDataAccessException("upstream HTTP 429"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody())
                .containsEntry("success", false)
                .containsEntry("error", "upstream HTTP 429");
    }
}
