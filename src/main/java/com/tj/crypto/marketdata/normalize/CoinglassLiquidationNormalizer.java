package com.tj.crypto.marketdata.normalize;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.OrderSide;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.LiquidationEvent;
import com.tj.crypto.pojo.dto.LiquidationOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Coinglass 爆仓数据标准化器。
 * 将 Coinglass WebSocket 推送的 LiquidationOrder DTO 转换为内部 LiquidationEvent。
 *
 * Coinglass 爆仓数据特点：
 * - 聚合多交易所的爆仓数据
 * - side: 1=多(Long), 2=空(Short)
 * - volUsd: USD 计价的爆仓金额
 */
@Slf4j
@Component
public class CoinglassLiquidationNormalizer {

    /**
     * 将 Coinglass LiquidationOrder 转换为 LiquidationEvent。
     *
     * @param order Coinglass 爆仓订单 DTO
     * @return LiquidationEvent，解析失败时返回 null
     */
    public LiquidationEvent normalize(LiquidationOrder order) {
        try {
            // Coinglass symbol 格式不统一：BTCUSDT, BTC-USDT-SWAP, BTC-USD
            // 统一转换为 BASEUSDT 格式
            String normalizedSymbol = normalizeSymbol(order.getSymbol(), order.getBaseAsset());
            Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, normalizedSymbol);

            OrderSide side;
            try {
                side = OrderSide.fromCode(order.getSide());
            } catch (IllegalArgumentException e) {
                log.warn("Dropping Coinglass liquidation with unknown side code: {}", order.getSide());
                return null;
            }

            if (order.getPrice() == null || order.getVolUsd() == null
                    || order.getTime() <= 0 || normalizedSymbol == null) return null;

            EventMetadata metadata = EventMetadata.of(Exchange.COINGLASS, order.getTime());

            return new LiquidationEvent(
                    instrument,
                    metadata,
                    side,
                    order.getPrice(),
                    BigDecimal.ZERO, // Coinglass 不直接提供基础资产数量
                    order.getVolUsd(),
                    order.getExName()
            );

        } catch (Exception e) {
            log.error("Failed to normalize Coinglass liquidation: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 标准化 Coinglass symbol 格式。
     * 输入可能是：BTCUSDT, BTC-USDT-SWAP, BTC-USD
     * 输出统一为：BTCUSDT
     */
    private String normalizeSymbol(String symbol, String baseAsset) {
        if (symbol == null && baseAsset == null) return null;
        if (symbol == null) return baseAsset + "USDT";

        // 已经是标准格式（无连字符）
        if (!symbol.contains("-")) {
            return symbol;
        }

        // BTC-USDT-SWAP → BTCUSDT
        // BTC-USD → BTCUSDT（假设 USD 等同于 USDT）
        String[] parts = symbol.split("-");
        if (parts.length >= 2) {
            String base = parts[0];
            String quote = parts[1];
            if ("USD".equals(quote)) quote = "USDT";
            return base + quote;
        }

        return symbol.replace("-", "");
    }
}
