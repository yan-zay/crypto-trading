package com.tj.crypto.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @Author zay
 * @Date 2025/9/12 16:43
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    public static final String THREAD_POOL_NAME = "tjTaskExecutor";

    @Bean(name = THREAD_POOL_NAME) // 指定Bean名称，用于@Async注解引用
    public ThreadPoolTaskExecutor tjTaskExecutor() {
        log.info("初始化自定义线程池");
        int core = Runtime.getRuntime().availableProcessors();
        int max = core * 4;
        log.info("自定义线程池参数, core:{}, max:{}", core, max);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 设置核心参数
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("tj-async-");

        // 设置拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待所有任务完成后关闭线程池（优雅关机）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30); // 等待时间

        // 初始化
        executor.initialize();
        return executor;
    }
}
