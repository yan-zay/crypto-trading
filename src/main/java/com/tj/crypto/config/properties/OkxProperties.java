package com.tj.crypto.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** OKX public market data connector and REST history settings. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.connector.okx")
public class OkxProperties {
    private boolean enabled = true;
    private String websocketUrl = "wss://ws.okx.com:8443/ws/v5/business";
    private String restBaseUrl = "https://www.okx.com";
    private List<String> instruments = new ArrayList<>(List.of(
            "BTC-USDT", "ETH-USDT", "BTC-USDT-SWAP", "ETH-USDT-SWAP"));
    private List<String> timeframes = new ArrayList<>(List.of("1m"));
}
