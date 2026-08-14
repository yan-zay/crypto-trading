package com.tj.crypto.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binance 历史数据 API 配置属性。
 * 绑定 crypto.binance.historical.* 前缀的配置项。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.binance.historical")
public class BinanceHistoricalDataProperties {

    /** Binance Spot REST API 基础地址。 */
    private String spotBaseUrl = "https://api.binance.com";

    /** Binance USD-M Futures REST API 基础地址。 */
    private String perpetualBaseUrl = "https://fapi.binance.com";

    /**
     * 保留旧配置的兼容访问器，语义是 USD-M 永续合约地址。
     */
    @Deprecated
    public String getBaseUrl() {
        return perpetualBaseUrl;
    }

    @Deprecated
    public void setBaseUrl(String baseUrl) {
        this.perpetualBaseUrl = baseUrl;
    }
}
