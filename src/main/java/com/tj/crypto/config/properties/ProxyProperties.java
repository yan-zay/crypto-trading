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

    /** 默认直连；只有显式启用时才使用本地 SOCKS 代理。 */
    private boolean enabled = false;
    private String host = "127.0.0.1";
    private Integer port = 10808;
}
