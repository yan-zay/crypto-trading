package com.tj.crypto.config.properties;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.MarketType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 当前产品边界中唯一可交易/可研究的市场宇宙。
 *
 * <p>所有外部请求都必须经过这里校验，避免连接器、回填和回测各自维护一套
 * 不一致的交易对白名单。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.market-data.universe")
public class MarketUniverseProperties {

    private List<Exchange> exchanges = new ArrayList<>(List.of(
            Exchange.BINANCE, Exchange.COINGLASS, Exchange.OKX));
    private List<MarketType> marketTypes = new ArrayList<>(List.of(
            MarketType.SPOT, MarketType.PERPETUAL));
    private List<String> symbols = new ArrayList<>(List.of("BTCUSDT", "ETHUSDT"));

    public void validate(Exchange exchange, MarketType marketType, String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (!exchanges.contains(exchange)) {
            throw new IllegalArgumentException("Unsupported exchange: " + exchange);
        }
        if (!marketTypes.contains(marketType)) {
            throw new IllegalArgumentException("Unsupported market type: " + marketType);
        }
        boolean supportedSymbol = symbols.stream()
                .map(MarketUniverseProperties::normalizeSymbol)
                .anyMatch(normalized::equals);
        if (!supportedSymbol) {
            throw new IllegalArgumentException(
                    "Unsupported symbol: " + symbol + "; allowed symbols are " + symbols);
        }
    }

    public static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        return symbol.replace("-", "").toUpperCase(Locale.ROOT);
    }
}
