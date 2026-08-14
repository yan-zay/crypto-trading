package com.tj.crypto.common.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * 交易工具的稳定业务主键。
 *
 * <p>symbol 只在单一交易所、单一市场内唯一。缓存、持仓、订单和数据库查询
 * 必须使用 exchange + marketType + symbol，避免同名现货、永续和跨交易所数据碰撞。
 */
public record InstrumentId(
        Exchange exchange,
        MarketType marketType,
        String symbol
) {
    public InstrumentId {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(marketType, "marketType");
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
    }

    public static InstrumentId from(Instrument instrument) {
        Objects.requireNonNull(instrument, "instrument");
        return new InstrumentId(instrument.exchange(), instrument.marketType(), instrument.symbol());
    }

    /** 可用于日志、外部价格快照和持久化关联的规范字符串。 */
    public String value() {
        return exchange.getCode() + ":" + marketType.getCode() + ":" + symbol;
    }

    @Override
    public String toString() {
        return value();
    }
}
