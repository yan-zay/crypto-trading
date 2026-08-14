package com.tj.crypto.backtest.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/** Claims durable jobs before dispatching them to a dedicated bounded executor. */
@Slf4j
@Component
public class BacktestJobWorker {
    private final BacktestJobMapper mapper;
    private final BacktestJobService service;
    @Qualifier("backtestJobExecutor")
    private final ThreadPoolTaskExecutor executor;
    private final String workerId = "backtest-" + UUID.randomUUID();

    public BacktestJobWorker(BacktestJobMapper mapper, BacktestJobService service,
                             @Qualifier("backtestJobExecutor") ThreadPoolTaskExecutor executor) {
        this.mapper = mapper;
        this.service = service;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        int requeued = mapper.recoverRunning();
        int cancelled = mapper.recoverCancellation(System.currentTimeMillis());
        if (requeued + cancelled > 0) {
            log.info("Recovered backtest jobs: requeued={}, cancelled={}", requeued, cancelled);
        }
    }

    @Scheduled(fixedDelayString = "${crypto.backtest-jobs.poll-interval-ms:1000}")
    public void poll() {
        for (String jobId : mapper.selectQueued(8)) {
            String resultId = UUID.randomUUID().toString();
            if (mapper.claim(jobId, resultId, workerId, System.currentTimeMillis()) != 1) continue;
            try {
                executor.execute(() -> service.executeClaimed(jobId));
            } catch (RejectedExecutionException e) {
                mapper.releaseClaim(jobId);
                break;
            }
        }
    }
}
