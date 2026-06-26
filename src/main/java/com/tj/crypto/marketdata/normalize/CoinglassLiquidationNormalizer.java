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
            // 使用 Coinglass 作为交易所标识（数据来自 Coinglass 聚合）
            Instrument instrument = Instrument.of(Exchange.COINGLASS, MarketType.PERPETUAL, order.getSymbol());

            OrderSide side;
            try {
                side = OrderSide.fromCode(order.getSide());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown side code: {}, defaulting to LONG", order.getSide());
                side = OrderSide.LONG;
            }

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
}
