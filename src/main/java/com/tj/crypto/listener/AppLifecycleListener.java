package com.tj.crypto.listener;

import com.tj.crypto.entity.TradeSymbolDO;
import com.tj.crypto.mapper.TradeSymbolMapper;
import lombok.AllArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Author zay
 * @Date 2025/9/12 17:35
 */
@Component
@AllArgsConstructor
public class AppLifecycleListener {

    private ThreadPoolTaskExecutor tjTaskExecutor;
    private TradeSymbolMapper tradeSymbolMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        List<TradeSymbolDO> symbolList = tradeSymbolMapper.selectList();
        for (TradeSymbolDO symbol : symbolList) {
            tjTaskExecutor.execute(() -> {

            });
        }
    }
}
