package com.tj.crypto.storage.service;

import com.tj.crypto.backtest.portfolio.Trade;
import com.tj.crypto.storage.converter.TradeConverter;
import com.tj.crypto.storage.entity.TradeRecordDO;
import com.tj.crypto.storage.mapper.TradeRecordMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 交易记录持久化服务。
 * 异步写入数据库。
 */
@Slf4j
@Service
@AllArgsConstructor
public class TradePersistenceService {

    private final TradeRecordMapper tradeRecordMapper;
    private final ThreadPoolTaskExecutor tjTaskExecutor;

    /**
     * 异步持久化 Trade。
     */
    public void persistTradeAsync(Trade trade) {
        tjTaskExecutor.execute(() -> {
            try {
                TradeRecordDO DO = TradeConverter.toDO(trade);
                tradeRecordMapper.insert(DO);
                log.debug("Persisted trade: {} {} PnL={}", trade.instrument().symbol(), trade.side(), trade.realizedPnL());
            } catch (Exception e) {
                log.error("Failed to persist trade: {}", e.getMessage(), e);
            }
        });
    }
}
