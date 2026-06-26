package com.tj.crypto.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author: zay
 * @Date: 2024-07-25 14:53
 */
@Data
@Component
@ConfigurationProperties(prefix = "crypto.websocket")
public class WebsocketProperties {

    private String baseUrl;
    private String websocketUrl;
}
