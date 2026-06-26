package com.tj.crypto.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用生命周期监听器。
 * 启动后输出就绪日志。
 */
@Slf4j
@Component
public class AppLifecycleListener {

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Crypto Trading Engine started and ready");
    }
}
