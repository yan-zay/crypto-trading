package com.tj.crypto.factor;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 技术因子配置属性。
 * 绑定 crypto.factor.* 前缀的配置项。
 *
 * 配置示例：
 * crypto:
 *   factor:
 *     sma-period: 20
 *     ema-period: 20
 *     macd-fast: 12
 *     macd-slow: 26
 *     macd-signal: 9
 *     rsi-period: 14
 *     bb-period: 20
 *     bb-std-dev: 2.0
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.factor")
public class FactorProperties {

    /** SMA 周期，默认 20 */
    private int smaPeriod = 20;

    /** EMA 周期，默认 20 */
    private int emaPeriod = 20;

    /** MACD 快线周期，默认 12 */
    private int macdFast = 12;

    /** MACD 慢线周期，默认 26 */
    private int macdSlow = 26;

    /** MACD 信号线周期，默认 9 */
    private int macdSignal = 9;

    /** RSI 周期，默认 14 */
    private int rsiPeriod = 14;

    /** Bollinger Bands 周期，默认 20 */
    private int bbPeriod = 20;

    /** Bollinger Bands 标准差倍数，默认 2.0 */
    private double bbStdDev = 2.0;
}
