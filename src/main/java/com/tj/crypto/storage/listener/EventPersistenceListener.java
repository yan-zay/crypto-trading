package com.tj.crypto.storage.listener;

import com.tj.crypto.event.MarketEventBus;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.storage.service.MarketDataPersistenceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 事件持久化监听器。
 * 订阅 MarketEventBus，自动将事件持久化到数据库。
 *
 * 设计决策：
 * - 仅持久化 closed=true 的 BarEvent（避免频繁写入）
 * - 异步写入，不阻塞事件总线
 * - 关闭时 flush 缓冲区
 */
@Slf4j
@Component
@AllArgsConstructor
public class EventPersistenceListener {

    private final MarketEventBus eventBus;
    private final MarketDataPersistenceService persistenceService;

    @PostConstruct
    public void init() {
        eventBus.subscribe(BarEvent.class, this::onBarEvent);
        log.info("EventPersistenceListener subscribed to BarEvent");
    }

    private void onBarEvent(BarEvent event) {
        // 仅持久化已完成的 K 线
        if (event.closed()) {
            persistenceService.persistBarAsync(event);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Flushing pending bar events...");
        persistenceService.flush();
    }
}
