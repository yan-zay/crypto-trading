package com.tj.crypto.backtest.portfolio;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 手续费配置属性。
 * 绑定 crypto.fee.* 前缀的配置项。
 *
 * 配置示例：
 * crypto:
 *   fee:
 *     maker-fee-rate: 0.0002
 *     taker-fee-rate: 0.0004
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.fee")
public class FeeProperties {

    /** Maker 手续费率（0.02% = 0.0002），默认 0.0002 */
    private BigDecimal makerFeeRate = new BigDecimal("0.0002");

    /** Taker 手续费率（0.04% = 0.0004），默认 0.0004 */
    private BigDecimal takerFeeRate = new BigDecimal("0.0004");
}
