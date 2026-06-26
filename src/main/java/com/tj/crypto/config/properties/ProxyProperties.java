package com.tj.crypto.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author zay
 * @Date 2025/9/16 10:01
 */
@Data
@Component
@ConfigurationProperties("crypto.proxy")
public class ProxyProperties {

    private String host;
    private Integer port;
}
