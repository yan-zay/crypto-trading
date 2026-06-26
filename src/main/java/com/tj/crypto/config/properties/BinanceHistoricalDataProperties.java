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

    /**
     * Binance Futures REST API 基础地址。
     */
    private String baseUrl = "https://fapi.binance.com";
}
