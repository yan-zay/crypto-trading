package com.tj.crypto.storage.service;

import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.storage.converter.BarEventConverter;
import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 市场数据持久化服务。
 * 异步写入数据库，不影响主流程。
 */
@Slf4j
@Service
@AllArgsConstructor
public class MarketDataPersistenceService {

    private final BarEventMapper barEventMapper;
    private final ThreadPoolTaskExecutor tjTaskExecutor;

    /** 批量缓冲区 */
    private final List<BarEventDO> buffer = new ArrayList<>();
    private static final int BATCH_SIZE = 100;

    /**
     * 异步持久化 BarEvent。
     */
    public void persistBarAsync(BarEvent event) {
        tjTaskExecutor.execute(() -> {
            try {
                BarEventDO DO = BarEventConverter.toDO(event);
                synchronized (buffer) {
                    buffer.add(DO);
                    if (buffer.size() >= BATCH_SIZE) {
                        flushBuffer();
                    }
                }
            } catch (Exception e) {
                log.error("Failed to persist bar event: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 批量写入缓冲区。
     */
    private void flushBuffer() {
        if (buffer.isEmpty()) return;
        try {
            barEventMapper.insertBatch(new ArrayList<>(buffer));
            log.debug("Flushed {} bar events to database", buffer.size());
            buffer.clear();
        } catch (Exception e) {
            log.error("Failed to flush bar events: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动 flush（关闭时调用）。
     */
    public void flush() {
        synchronized (buffer) {
            flushBuffer();
        }
    }
}
