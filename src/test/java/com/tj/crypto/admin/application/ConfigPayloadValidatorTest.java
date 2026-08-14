package com.tj.crypto.admin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.domain.ConfigType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigPayloadValidatorTest {

    private ConfigPayloadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConfigPayloadValidator(new ObjectMapper());
    }

    @Test
    void acceptsValidTypedConfiguration() {
        assertThatCode(() -> validator.validate(ConfigType.RISK,
                "{\"maxDailyLossPct\":5,\"maxSizePct\":30.5,\"slippageBps\":5}"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNumericStringsInsteadOfSilentlyCoercingThem() {
        assertThatThrownBy(() -> validator.validate(ConfigType.RISK,
                "{\"maxDailyLossPct\":\"5\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be numeric");
    }

    @Test
    void rejectsFractionalValuesForIntegerFields() {
        assertThatThrownBy(() -> validator.validate(ConfigType.FACTOR,
                "{\"rsiPeriod\":14.9}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an integer");
    }

    @Test
    void rejectsInvalidConnectorProtocol() {
        assertThatThrownBy(() -> validator.validate(ConfigType.CONNECTOR,
                "{\"okxWsUrl\":\"https://ws.okx.com/ws/v5/business\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ws/wss");
    }
}
