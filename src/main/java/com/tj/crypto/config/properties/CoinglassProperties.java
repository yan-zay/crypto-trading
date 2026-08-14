package com.tj.crypto.config.properties;

import com.tj.crypto.common.domain.MarketType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    private String websocketUrl = "wss://open-ws.coinglass.com/ws-api";

    /** Independent lifecycle switch for the liquidation WebSocket. */
    private boolean websocketEnabled = true;

    /** Coinglass API v4 REST 根地址。 */
    private String restBaseUrl = "https://open-api-v4.coinglass.com";

    /** Coinglass 价格接口中的底层交易所。Coinglass 本身不是成交场所。 */
    private String priceExchange = "Binance";

    /** 是否启动最新已完成 K 线的 REST 增量轮询。 */
    private boolean klinePollingEnabled = true;

    /** REST 轮询间隔；最小值由连接器校验。 */
    private long klinePollingIntervalMs = 60_000L;

    private List<String> symbols = new ArrayList<>(List.of("BTCUSDT", "ETHUSDT"));
    private List<String> timeframes = new ArrayList<>(List.of("1m"));
    private List<MarketType> marketTypes = new ArrayList<>(List.of(
            MarketType.SPOT, MarketType.PERPETUAL));
}
