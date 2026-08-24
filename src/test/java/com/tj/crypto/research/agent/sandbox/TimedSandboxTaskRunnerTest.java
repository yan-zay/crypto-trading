package com.tj.crypto.research.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimedSandboxTaskRunnerTest {

    @Test
    @DisplayName("实际沙盒 runner 超时会取消纯计算任务")
    void shouldCancelTimedOutTask() throws InterruptedException {
        TimedSandboxTaskRunner runner = new TimedSandboxTaskRunner(Executors.newSingleThreadExecutor());
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> runner.run(Duration.ofMillis(30), () -> {
                try {
                    Thread.sleep(10_000);
                    return "unexpected";
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw e;
                }
            })).isInstanceOf(SandboxTimeoutException.class);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.close();
        }
    }
}
