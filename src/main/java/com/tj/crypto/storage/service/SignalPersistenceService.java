package com.tj.crypto.storage.service;

import com.tj.crypto.storage.converter.SignalEventConverter;
import com.tj.crypto.storage.entity.SignalEventDO;
import com.tj.crypto.storage.mapper.SignalEventMapper;
import com.tj.crypto.strategy.core.SignalEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 信号持久化服务。
 * 异步写入数据库。
 */
@Slf4j
@Service
@AllArgsConstructor
public class SignalPersistenceService {

    private final SignalEventMapper signalEventMapper;
    private final ThreadPoolTaskExecutor tjTaskExecutor;

    /**
     * 异步持久化 SignalEvent。
     */
    public void persistSignalAsync(SignalEvent event) {
        tjTaskExecutor.execute(() -> {
            try {
                SignalEventDO dobj = SignalEventConverter.toDO(event);
                signalEventMapper.insert(dobj);
                log.debug("Persisted signal: {} {} {}", event.strategyName(), event.type(), event.instrument().symbol());
            } catch (Exception e) {
                log.error("Failed to persist signal event: {}", e.getMessage(), e);
            }
        });
    }
}
