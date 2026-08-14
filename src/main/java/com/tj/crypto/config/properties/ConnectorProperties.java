package com.tj.crypto.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接器配置属性。
 * 绑定 crypto.connector.* 前缀的配置项。
 *
 * <p>支持运行态动态更新（ConfigSyncService 调用 setter）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.connector")
public class ConnectorProperties {

    /** Binance 订阅交易对列表。 */
    private List<String> symbols = new ArrayList<>(List.of("BTCUSDT", "ETHUSDT"));

    /** Binance 行情连接器开关。 */
    private boolean binanceEnabled = true;

    /** Binance 现货 combined-stream WebSocket URL。 */
    private String binanceSpotWsUrl = "wss://stream.binance.com:9443/stream";

    /** Binance USD-M 永续合约 combined-stream WebSocket URL。 */
    private String binancePerpetualWsUrl = "wss://fstream.binance.com/stream";

    /** Binance 实时订阅周期。历史回填支持 Timeframe 的全部周期。 */
    private List<String> binanceTimeframes = new ArrayList<>(List.of("1m"));

    /** Coinglass 爆仓 WebSocket URL。 */
    private String coinglassWsUrl = "wss://open-ws.coinglass.com/ws-api";

    /** 自动重连间隔（秒） */
    private int reconnectIntervalSec = 5;

    /** 最大重连次数（0=无限） */
    private int maxReconnectAttempts = 0;

    /** 健康检查间隔（秒） */
    private int healthCheckIntervalSec = 30;
}
