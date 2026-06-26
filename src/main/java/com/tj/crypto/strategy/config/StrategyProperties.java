package com.tj.crypto.strategy.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略配置属性。
 * 绑定 crypto.strategy.* 前缀的配置项。
 *
 * 配置示例：
 * crypto:
 *   strategy:
 *     liquidation-spike:
 *       enabled: true
 *       threshold-usd: 1000000
 *     macd-cross:
 *       enabled: true
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.strategy")
public class StrategyProperties {

    /** 策略配置 Map（key = 策略名称） */
    private Map<String, StrategyConfig> configs = new HashMap<>();

    @Getter
    @Setter
    public static class StrategyConfig {
        /** 是否启用 */
        private boolean enabled = true;
        /** 爆仓阈值（USD） */
        private BigDecimal thresholdUsd = new BigDecimal("1000000");
        /** 监听的交易对列表 */
        private List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
    }
}
