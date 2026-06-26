package com.tj.crypto.common.domain;

/**
 * 交易工具，不可变值对象。
 * 同一交易对在不同交易所/市场类型是不同的 Instrument。
 *
 * @param exchange    交易所
 * @param marketType  市场类型
 * @param symbol      交易对符号，如 "BTCUSDT"
 * @param baseAsset   基础资产，如 "BTC"
 * @param quoteAsset  计价资产，如 "USDT"
 */
public record Instrument(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        String baseAsset,
        String quoteAsset
) {
    /**
     * 从交易所原始 symbol 字符串构造 Instrument。
     * 简单拆分：从已知 quote asset 列表中匹配。
     * 复杂情况（如 "1000PEPEUSDT"）需要交易所特定的解析逻辑。
     */
    public static Instrument of(Exchange exchange, MarketType marketType, String symbol) {
        String[] quotes = {"USDT", "BUSD", "USDC", "BTC", "ETH", "BNB"};
        for (String quote : quotes) {
            if (symbol.endsWith(quote) && symbol.length() > quote.length()) {
                String base = symbol.substring(0, symbol.length() - quote.length());
                return new Instrument(exchange, marketType, symbol, base, quote);
            }
        }
        return new Instrument(exchange, marketType, symbol, symbol, "");
    }
}
