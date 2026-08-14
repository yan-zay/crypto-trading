package com.tj.crypto.marketdata.okx;

import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.Timeframe;

import java.util.Locale;

/** OKX native instrument/channel naming and internal domain naming conversions. */
public final class OkxMarketDataMappings {

    private static final String[] QUOTE_ASSETS = {"USDT", "USDC", "USD", "BTC", "ETH"};

    private OkxMarketDataMappings() {}

    public static String toInternalSymbol(String instrumentId) {
        String normalized = instrumentId.toUpperCase(Locale.ROOT);
        if (normalized.endsWith("-SWAP")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        return normalized.replace("-", "");
    }

    public static String toOkxInstrumentId(String symbol, MarketType marketType) {
        if (marketType != MarketType.SPOT && marketType != MarketType.PERPETUAL) {
            throw new IllegalArgumentException(
                    "OKX dated futures require an explicit native instrument id and are not supported yet");
        }
        String normalized = symbol.toUpperCase(Locale.ROOT);
        if (normalized.contains("-")) {
            if (marketType == MarketType.PERPETUAL && !normalized.endsWith("-SWAP")) {
                return normalized + "-SWAP";
            }
            return normalized;
        }
        for (String quote : QUOTE_ASSETS) {
            if (normalized.endsWith(quote) && normalized.length() > quote.length()) {
                String nativeSymbol = normalized.substring(0, normalized.length() - quote.length())
                        + "-" + quote;
                return marketType == MarketType.PERPETUAL ? nativeSymbol + "-SWAP" : nativeSymbol;
            }
        }
        throw new IllegalArgumentException("Cannot map symbol to OKX instrument: " + symbol);
    }

    public static MarketType marketType(String instrumentId) {
        String normalized = instrumentId.toUpperCase(Locale.ROOT);
        if (normalized.endsWith("-SWAP")) return MarketType.PERPETUAL;
        if (normalized.split("-").length == 2) return MarketType.SPOT;
        throw new IllegalArgumentException(
                "OKX dated futures are not supported by the candle connector: " + instrumentId);
    }

    public static String websocketChannel(Timeframe timeframe) {
        return "candle" + switch (timeframe) {
            case M1 -> "1m";
            case M5 -> "5m";
            case M15 -> "15m";
            case M30 -> "30m";
            case H1 -> "1H";
            case H4 -> "4H";
            case D1 -> "1Dutc";
        };
    }

    public static String restBar(Timeframe timeframe) {
        return websocketChannel(timeframe).substring("candle".length());
    }

    public static Timeframe timeframeFromChannel(String channel) {
        if (channel == null || !channel.startsWith("candle")) {
            throw new IllegalArgumentException("Unsupported OKX channel: " + channel);
        }
        return timeframeFromNativeCode(channel.substring("candle".length()));
    }

    public static Timeframe timeframeFromNativeCode(String code) {
        return switch (code) {
            case "1m" -> Timeframe.M1;
            case "5m" -> Timeframe.M5;
            case "15m" -> Timeframe.M15;
            case "30m" -> Timeframe.M30;
            case "1H" -> Timeframe.H1;
            case "4H" -> Timeframe.H4;
            case "1D", "1Dutc" -> Timeframe.D1;
            default -> throw new IllegalArgumentException("Unsupported OKX timeframe: " + code);
        };
    }
}
