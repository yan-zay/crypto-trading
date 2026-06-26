package com.tj.crypto.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Coinglass API 配置属性。
 * 绑定 crypto.coinglass.* 前缀的配置项。
 *
 * 配置示例（application.yml）：
 * crypto:
 *   coinglass:
 *     api-key: ${COINGLASS_API_KEY:}
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.coinglass")
public class CoinglassProperties {

    /**
     * Coinglass API Key。
     * 优先从环境变量 COINGLASS_API_KEY 读取。
     */
    private String apiKey;
}
